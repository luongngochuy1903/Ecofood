package com.ecofood.app.customer.service;

import com.ecofood.app.customer.config.CustomerSettings;
import com.ecofood.app.customer.entity.CustomerInvoice;
import com.ecofood.app.customer.security.CustomerAccess;
import com.ecofood.app.customer.security.CustomerIdentity;
import com.ecofood.app.customer.security.CustomerReceiptTokens;
import com.ecofood.app.notification.config.NotificationSettings;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.dto.CustomerReceiptDtos;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.CustomerReceiptService;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CustomerReceiptServiceImpl implements CustomerReceiptService {
  private static final String INVOICE_HOLDER_USER_ID = "invoiceHolder.userId";
  private static final String INVOICE_HOLDER_CUSTOMER_ID = "invoiceHolder.customerId";
  private static final String RECEIPT_REVOKED_AT = "customerReceipt.revokedAt";
  private static final String TENANT_ID = "tenantId";
  public static final String CLAIMED = "INVOICE_CLAIMED";
  private final MongoTemplate mongo;
  private final CustomerAccess access;
  private final CustomerSettings settings;
  private final CustomerReceiptTokens tokens;
  private final CustomerIdentity identity;
  private final NotificationSettings mail;
  private final CustomerClaimHandoff handoff;
  private record Seller(String user, String tenant) {}
  private Seller seller() {
    String user;
    String tenant;
    try { user = AuthenticationContext.getUserId().toHexString(); tenant = AuthenticationContext.getTenantId().toHexString(); }
    catch (RuntimeException invalid) { throw CustomerAccess.denied(); }
    var record = mongo.findById(new ObjectId(user), UserEntity.class);
    if (record == null || Boolean.TRUE.equals(record.getDeleted()) || !CustomerIdentity.belongs(record, tenant)
        || record.getUserCategory() == null || !"PARTNER".equals(record.getUserCategory().getUserCategoryCode())) throw CustomerAccess.denied();
    return new Seller(user, tenant);
  }
  private Query source(String id, Seller seller) {
    return Query.query(Criteria.where("_id").is(id).and(TENANT_ID).is(seller.tenant()).and("submittedBy").is(seller.user())
        .and("posId").is(seller.user()).and("status").is(TransactionStatus.ACCEPTED));
  }
  private Criteria unowned() { return Criteria.where(INVOICE_HOLDER_USER_ID).is(null).and(INVOICE_HOLDER_CUSTOMER_ID).is(null); }
  @Override
  public CustomerReceiptDtos.Issued issue(String invoiceId, CustomerReceiptDtos.IssueRequest request) {
    var seller = seller(); String requestId;
    try { requestId = UUID.fromString(request.requestId()).toString(); if (!requestId.equalsIgnoreCase(request.requestId())) throw new IllegalArgumentException(); }
    catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A full UUID requestId is required"); }
    var query = source(invoiceId, seller); query.addCriteria(unowned());
    var invoice = mongo.findOne(query, CustomerInvoice.class); if (invoice == null) throw CustomerServiceImpl.unavailable();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var old = invoice.getCustomerReceipt();
    CustomerInvoice.Receipt receipt;
    if (old != null && old.requestId().equals(requestId)) {
      if (old.revokedAt() != null || !old.expiresAt().isAfter(now)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt expired or revoked; use a new requestId");
      }
      receipt = old;
    } else {
      if (old != null && old.revokedAt() == null && old.expiresAt().isAfter(now)) throw new ResponseStatusException(HttpStatus.CONFLICT, "An active receipt already exists");
      // A new generation cannot revive an old revoked capability, even if request IDs/timestamps repeat.
      String id = tokens.id(seller.tenant(), seller.user(), invoiceId, requestId + "\n" + UUID.randomUUID());
      String token = tokens.token(id, now);
      receipt = new CustomerInvoice.Receipt(id, requestId, CustomerReceiptTokens.hash(token), seller.user(), now, now.plus(Duration.ofHours(settings.receiptHours)), null);
      // Validate trusted origin/QR before persisting the new capability.
      tokens.qr(tokens.url(token));
      var update = source(invoiceId, seller); update.addCriteria(unowned());
      if (old == null) update.addCriteria(Criteria.where("customerReceipt").is(null));
      else update.addCriteria(Criteria.where("customerReceipt.id").is(old.id()).and(RECEIPT_REVOKED_AT).is(old.revokedAt()));
      var saved = mongo.findAndModify(update, new Update().set("customerReceipt", receipt), FindAndModifyOptions.options().returnNew(true), CustomerInvoice.class);
      if (saved == null) {
        saved = mongo.findOne(query, CustomerInvoice.class);
        if (saved == null || saved.getCustomerReceipt() == null || !saved.getCustomerReceipt().requestId().equals(requestId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt changed; reload");
        receipt = saved.getCustomerReceipt();
        if (receipt.revokedAt() != null || !receipt.expiresAt().isAfter(now)) throw CustomerServiceImpl.unavailable();
      }
    }
    String raw = tokens.token(receipt.id(), receipt.issuedAt());
    if (!CustomerReceiptTokens.hash(raw).equals(receipt.tokenHash())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Receipt signing key changed; revoke and issue a new receipt");
    String url = tokens.url(raw);
    return new CustomerReceiptDtos.Issued(receipt.id(), invoiceId, url, tokens.qr(url), receipt.expiresAt());
  }
  @Override
  public void revoke(String invoiceId) {
    var seller = seller();
    var query = source(invoiceId, seller);
    query.addCriteria(Criteria.where("customerReceipt.id").exists(true));
    if (!mongo.exists(query, CustomerInvoice.class)) throw CustomerServiceImpl.unavailable();
    query.addCriteria(Criteria.where(RECEIPT_REVOKED_AT).is(null));
    mongo.updateFirst(query, new Update().set(RECEIPT_REVOKED_AT, Instant.now()).set("customerReceipt.revokedBy", seller.user()), CustomerInvoice.class);
  }
  private Criteria valid(String token, Instant now) {
    CustomerReceiptTokens.validate(token);
    return Criteria.where("customerReceipt.tokenHash").is(CustomerReceiptTokens.hash(token)).and(RECEIPT_REVOKED_AT).is(null)
        .and("customerReceipt.expiresAt").gt(now).and("status").is(TransactionStatus.ACCEPTED);
  }
  @Override
  public CustomerReceiptDtos.GuestInvoice view(String token) {
    var invoice = mongo.findOne(Query.query(new Criteria().andOperator(valid(token, Instant.now()), unowned())), CustomerInvoice.class);
    if (invoice == null) throw CustomerServiceImpl.unavailable();
    var safe = CustomerServiceImpl.invoice(invoice);
    return new CustomerReceiptDtos.GuestInvoice(safe.id(), safe.transactionTime(), safe.currency(), safe.totalAmount(), safe.items(), identity.config(invoice.getTenantId()));
  }
  @Override
  public CustomerReceiptDtos.Claimed claim(String token) {
    var actor = access.current(); CustomerReceiptTokens.validate(token);
    String hash = CustomerReceiptTokens.hash(token);
    var prior = mongo.findOne(Query.query(Criteria.where("customerReceipt.tokenHash").is(hash).and(TENANT_ID).is(actor.tenantId())
        .and(INVOICE_HOLDER_USER_ID).is(actor.userId()).and(INVOICE_HOLDER_CUSTOMER_ID).is(actor.userId())), CustomerInvoice.class);
    if (prior != null) { handoff.deliver(prior); return result(prior); }
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Query query = Query.query(new Criteria().andOperator(valid(token, now), unowned(), Criteria.where(TENANT_ID).is(actor.tenantId())));
    var invoice = mongo.findOne(query, CustomerInvoice.class); if (invoice == null) throw CustomerServiceImpl.unavailable();
    String eventId = invoice.getId() + ":INVOICE_CLAIMED";
    var event = new Submission(eventId, CLAIMED, now, actor.tenantId(), "INVOICE", invoice.getId(), invoice.getPropositionId(), invoice.getPlanId(), invoice.getOrchestratorId(), invoice.getOrchestratorVersion());
    Notification notice = new Notification(); notice.setId(CustomerReceiptTokens.hash(eventId + ":customer-email")); notice.setSubmissionId(eventId);
    notice.setTenantId(actor.tenantId()); notice.setRuleId("UC021_INVOICE_LINKED"); notice.setType(CLAIMED); notice.setRuleRevision(1);
    notice.setRecipientUserId(actor.userId()); notice.setSendTo(NotificationSettings.canonical(actor.user().getEmail())); notice.setSendFrom(mail.from);
    notice.setSubject("Hóa đơn " + String.valueOf(invoice.getId()).replace('\r', ' ').replace('\n', ' '));
    notice.setContent("Hóa đơn đã được liên kết với tài khoản của bạn.\n" + settings.base() + "/customer/invoices/" + invoice.getId() + "?tenant=" + actor.tenantId());
    notice.setCreatedAt(now); notice.setNextAttemptAt(now);
    var saved = mongo.findAndModify(query, new Update().set("invoiceHolder", new com.ecofood.app.transaction.entity.Invoice.InvoiceHolder(actor.userId(), actor.userId()))
        .set("customerOwnership", new CustomerInvoice.Ownership(actor.userId(), now, "RECEIPT_TOKEN", invoice.getCustomerReceipt().id()))
        .set("customerClaimHandoff", new CustomerInvoice.Handoff(event, notice, true, now, 0)), FindAndModifyOptions.options().returnNew(true), CustomerInvoice.class);
    if (saved == null) {
      saved = mongo.findOne(Query.query(Criteria.where("_id").is(invoice.getId()).and(TENANT_ID).is(actor.tenantId()).and(INVOICE_HOLDER_USER_ID).is(actor.userId()).and(INVOICE_HOLDER_CUSTOMER_ID).is(actor.userId())), CustomerInvoice.class);
      if (saved == null) throw CustomerServiceImpl.unavailable();
    }
    handoff.deliver(saved); return result(saved);
  }
  private CustomerReceiptDtos.Claimed result(CustomerInvoice invoice) {
    String status = "PENDING_HANDOFF";
    if (invoice.getCustomerClaimHandoff() != null) {
      var notice = mongo.findById(invoice.getCustomerClaimHandoff().notification().getId(), Notification.class);
      if (notice != null) status = notice.getStatus();
    }
    return new CustomerReceiptDtos.Claimed(CustomerServiceImpl.invoice(invoice), status);
  }
}
