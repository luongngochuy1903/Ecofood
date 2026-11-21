package com.ecofood.app.customer;

import com.ecofood.app.notification.*;
import com.ecofood.app.submission.entity.Submission;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable owner-confirmation handoff, feeding the existing Submission and Notification collections. */
@Service
@RequiredArgsConstructor
public class CustomerClaimHandoff {
  private final MongoTemplate mongo;
  private final NotificationSettings mail;
  @Scheduled(fixedDelayString = "${ecofood.customer.handoff-poll-ms:10000}", initialDelayString = "${ecofood.customer.handoff-initial-ms:20000}")
  public void retry() {
    try { for (var invoice : mongo.find(Query.query(Criteria.where("customerClaimHandoff.pending").is(true).and("customerClaimHandoff.nextRetryAt").lte(Instant.now())).limit(100), CustomerInvoice.class)) deliver(invoice); }
    catch (RuntimeException unavailable) { /* Persisted Invoice handoff survives an outage. */ }
  }
  public void deliver(CustomerInvoice invoice) {
    var work = invoice.getCustomerClaimHandoff();
    if (work == null || !work.pending()) return;
    Query owned = Query.query(Criteria.where("_id").is(invoice.getId()).and("tenantId").is(invoice.getTenantId())
        .and("invoiceHolder.userId").is(work.notification().getRecipientUserId()).and("customerClaimHandoff.event.id").is(work.event().id()).and("customerClaimHandoff.pending").is(true));
    try {
      if (!CustomerReceiptService.CLAIMED.equals(work.event().event()) || !Objects.equals(work.event().sourceId(), invoice.getId())
          || invoice.getInvoiceHolder() == null || !Objects.equals(invoice.getInvoiceHolder().userId(), work.notification().getRecipientUserId())) throw new IllegalStateException("Invalid claim handoff");
      try { mongo.insert(work.event()); }
      catch (DuplicateKeyException duplicate) { var existing = mongo.findById(work.event().id(), Submission.class); if (!work.event().equals(existing)) throw new IllegalStateException("Claim event conflict"); }
      var notice = work.notification();
      if (!NotificationSettings.validEmail(notice.getSendFrom())) {
        if (!NotificationSettings.validEmail(mail.from)) throw new IllegalStateException("SMTP sender not configured"); notice.setSendFrom(mail.from);
      }
      try { mongo.insert(notice); }
      catch (DuplicateKeyException duplicate) {
        var existing = mongo.findById(notice.getId(), Notification.class);
        if (existing == null || !Objects.equals(existing.getTenantId(), notice.getTenantId()) || !Objects.equals(existing.getSubmissionId(), notice.getSubmissionId())
            || !Objects.equals(existing.getRecipientUserId(), notice.getRecipientUserId()) || !Objects.equals(existing.getSendTo(), notice.getSendTo())) throw new IllegalStateException("Claim delivery conflict");
      }
      mongo.updateFirst(owned, new Update().set("customerClaimHandoff.pending", false).unset("customerClaimHandoff.lastError"), CustomerInvoice.class);
    } catch (RuntimeException failure) {
      try { mongo.updateFirst(owned, new Update().inc("customerClaimHandoff.attempts", 1).set("customerClaimHandoff.nextRetryAt", Instant.now().plusSeconds(60))
          .set("customerClaimHandoff.lastError", "CLAIM_HANDOFF_RETRY:" + failure.getClass().getSimpleName()), CustomerInvoice.class); }
      catch (RuntimeException unavailable) { /* Retry remains durable; never undo ownership. */ }
    }
  }
}
