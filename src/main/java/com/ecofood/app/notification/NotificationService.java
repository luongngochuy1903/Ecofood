package com.ecofood.app.notification;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.customer.auth.CustomerIdentityService.CustomerSession;
import com.ecofood.app.customer.auth.CustomerTenantResolver;
import com.ecofood.app.customer.dto.CustomerPurchaseFilter;
import com.ecofood.app.customer.repository.CustomerPurchaseRepository;
import com.ecofood.app.notification.NotificationContracts.*;
import com.ecofood.core.dto.PageResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class NotificationService {
  private final CustomerIdentityService identities;
  private final CustomerTenantResolver portal;
  private final CustomerPurchaseRepository purchases;
  private final NotificationPreferenceService preferences;
  private final NotificationRepository notifications;
  private final MongoTemplate mongo;
  private final Clock clock;
  private final com.ecofood.app.complaint.ComplaintNotificationSource complaintNotifications;

  /** Trusted server-side entry point only. Task 1 deliberately has no HTTP create/send API. */
  public CreationResult create(Event event) {
    var resolved = resolve(event);
    Event canonical = resolved.event();
    var existing = duplicate(canonical, resolved.portalId());
    if (existing.isPresent()) return repeated(existing.get(), canonical);
    boolean allowed = preferences.allows(preferences.effective(canonical.recipientCustomerId(), resolved.portalId()), canonical.type(), canonical.deliveryChannel());
    var value = new Notification(); value.setRecipientCustomerId(canonical.recipientCustomerId()); value.setCustomerTenantId(resolved.portalId());
    value.setSourceTenantId(canonical.sourceTenantId()); value.setDistributionPartnerId(canonical.distributionPartnerId()); value.setType(canonical.type());
    value.setTitle(resolved.title()); value.setMessage(resolved.message()); value.setReferenceType(canonical.referenceType()); value.setReferenceId(canonical.referenceId());
    value.setActionTarget(resolved.target()); value.setDeliveryChannel(canonical.deliveryChannel());
    value.setDeliveryStatus(!allowed ? DeliveryStatus.SUPPRESSED : canonical.deliveryChannel() == Channel.IN_APP ? DeliveryStatus.AVAILABLE : DeliveryStatus.PENDING);
    value.setSuppressionReason(allowed ? null : "PREFERENCE_DISABLED"); value.setEventId(canonical.eventId()); value.setEventFingerprint(fingerprint(canonical));
    value.setCreatedAt(now()); value.setUpdatedAt(now()); value.setCreatedBy("NOTIFICATION_SERVICE"); value.setUpdatedBy("NOTIFICATION_SERVICE");
    try { var saved = notifications.insert(value); return new CreationResult(saved.getIdAsString(), saved.getDeliveryStatus(), false); }
    catch (DuplicateKeyException ex) {
      var winner = duplicate(canonical, resolved.portalId());
      if (winner.isEmpty()) throw ex;
      return repeated(winner.get(), canonical);
    }
  }

  /** Future delivery adapters must recheck preferences, identity and resource access immediately before sending. */
  public DeliveryDecision prepareDelivery(String id) {
    var value = notifications.findById(objectId(id)).orElseThrow(() -> new IllegalArgumentException("Notification not found"));
    if (value.getReferenceType() == ReferenceType.FEEDBACK) return new DeliveryDecision(false, "REFERENCE_RETIRED");
    if (Boolean.TRUE.equals(value.getDeleted()) || value.getDeliveryChannel() != Channel.EMAIL || value.getDeliveryStatus() != DeliveryStatus.PENDING)
      return new DeliveryDecision(false, "NOT_PENDING_EMAIL");
    var event = new Event(value.getEventId(), value.getRecipientCustomerId(), value.getSourceTenantId(), value.getDistributionPartnerId(),
        value.getType(), value.getReferenceType(), value.getReferenceId(), value.getDeliveryChannel());
    var resolved = resolve(event);
    if (!resolved.portalId().equals(value.getCustomerTenantId()) || !fingerprint(resolved.event()).equals(value.getEventFingerprint()))
      return new DeliveryDecision(false, "EVENT_CONTEXT_CHANGED");
    boolean allowed = preferences.allows(preferences.effective(value.getRecipientCustomerId(), value.getCustomerTenantId()), value.getType(), value.getDeliveryChannel());
    return new DeliveryDecision(allowed, allowed ? "AUTHORIZED" : "PREFERENCE_DISABLED");
  }

  public PageResponse<NotificationView> list(Type type, Boolean isRead, int page, int size) {
    var scope = identities.requirePurchaseSession();
    if (page < 0 || size < 1 || size > 100) throw new BadRequestException("page must be >= 0 and size between 1 and 100");
    var query = inbox(scope, type); if (isRead != null) query.addCriteria(Criteria.where("read").is(isRead));
    long total = mongo.count(query, Notification.class);
    query.with(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    return PageResponse.<NotificationView>builder().content(mongo.find(query, Notification.class).stream().map(this::view).toList())
        .page(page).size(size).totalElements(total).totalPages((int) ((total + size - 1) / size)).build();
  }

  public UnreadCount unreadCount(Type type) {
    var query = inbox(identities.requirePurchaseSession(), type).addCriteria(Criteria.where("read").is(false));
    return new UnreadCount(mongo.count(query, Notification.class));
  }

  public NotificationView markRead(String id, boolean read) {
    var scope = writeScope(); ObjectId identifier = objectId(id);
    var query = inbox(scope, null).addCriteria(Criteria.where("_id").is(identifier)).addCriteria(Criteria.where("read").ne(read));
    var update = new Update().set("read", read).set("updatedAt", now()).set("updatedBy", scope.customerId());
    if (read) update.set("readAt", now()); else update.unset("readAt");
    var result = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), Notification.class);
    if (result == null) result = mongo.findOne(inbox(scope, null).addCriteria(Criteria.where("_id").is(identifier)), Notification.class);
    if (result == null) throw new IllegalArgumentException("Notification not found");
    return view(result);
  }

  public ReadAllResult readAll() {
    var scope = writeScope(); Instant cutoff = now();
    var query = inbox(scope, null).addCriteria(Criteria.where("read").is(false)).addCriteria(Criteria.where("createdAt").lte(cutoff));
    var result = mongo.updateMulti(query, new Update().set("read", true).set("readAt", cutoff).set("updatedAt", cutoff).set("updatedBy", scope.customerId()), Notification.class);
    return new ReadAllResult(result.getModifiedCount(), cutoff);
  }

  Query inbox(CustomerSession scope, Type type) {
    var criteria = Criteria.where("recipientCustomerId").is(scope.customerId()).and("customerTenantId").is(scope.customerTenantId()).and("deleted").ne(true)
        .and("deliveryChannel").is(Channel.IN_APP).and("deliveryStatus").in(DeliveryStatus.AVAILABLE, DeliveryStatus.SENT)
        .and("referenceType").ne(ReferenceType.FEEDBACK); // legacy documents remain archived/readable by maintenance only
    if (type != null) criteria.and("type").is(type);
    return new Query(criteria);
  }
  private CustomerSession writeScope() {
    var user = identities.requireSelfServiceAccount("CUSTOMER_NOTIFICATION_MANAGE");
    return new CustomerSession(user.getIdAsString(), user.getPartyRelationships().get(0).getToPartyId().toHexString());
  }
  private Optional<Notification> duplicate(Event event, String portalId) {
    return notifications.findByRecipientCustomerIdAndCustomerTenantIdAndSourceTenantIdAndEventIdAndDeliveryChannel(event.recipientCustomerId(), portalId,
        event.sourceTenantId(), event.eventId(), event.deliveryChannel());
  }
  private CreationResult repeated(Notification value, Event event) {
    if (!fingerprint(event).equals(value.getEventFingerprint())) throw new IllegalStateException("Event identifier already used with another notification context");
    return new CreationResult(value.getIdAsString(), value.getDeliveryStatus(), true);
  }

  private Resolved resolve(Event input) {
    if (input != null && input.referenceType() == ReferenceType.FEEDBACK) throw new BadRequestException("Retired notification reference");
    if (input == null || input.eventId() == null || !input.eventId().matches("[A-Za-z0-9:_.-]{1,128}")
        || input.type() == null || input.referenceType() == null || input.deliveryChannel() == null) throw new BadRequestException("Invalid notification event");
    var event = new Event(input.eventId(), objectId(input.recipientCustomerId()).toHexString(), objectId(input.sourceTenantId()).toHexString(),
        input.distributionPartnerId(), input.type(), input.referenceType(), objectId(input.referenceId()).toHexString(), input.deliveryChannel());
    var customer = identities.requireAccount(event.recipientCustomerId()); var customerPortal = portal.resolve();
    if (!customer.getPartyRelationships().get(0).getToPartyId().toHexString().equals(customerPortal.tenantId())) throw new BadRequestException("Invalid recipient identity");
    var scope = new CustomerSession(customer.getIdAsString(), customerPortal.tenantId());
    if (event.referenceType() == ReferenceType.COMPLAINT) {
      if (event.type() != Type.COMPLAINT || event.deliveryChannel() != Channel.IN_APP) throw new BadRequestException("Invalid Complaint notification channel or type");
      var notice = complaintNotifications.forNotification(event.eventId(), event.referenceId());
      if (!scope.customerId().equals(notice.customerId()) || !scope.customerTenantId().equals(notice.homeTenantId())
          || !event.sourceTenantId().equals(notice.sourceTenantId()) || !Objects.equals(event.distributionPartnerId(), notice.distributionPartnerId()))
        throw new BadRequestException("Invalid Complaint notification recipient or source");
      return new Resolved(event, customerPortal.tenantId(), notice.title(), notice.message(), new ActionTarget(ActionKind.COMPLAINT_DETAIL, event.referenceId()));
    }
    if (event.type() == Type.COMPLAINT) throw new BadRequestException("Invalid Complaint notification reference");
    if (event.referenceType() == ReferenceType.ACCOUNT) {
      if (event.type() != Type.ACCOUNT || !event.referenceId().equals(scope.customerId()) || !event.sourceTenantId().equals(customerPortal.tenantId()) || event.distributionPartnerId() != null)
        throw new BadRequestException("Invalid account event context");
      return new Resolved(event, customerPortal.tenantId(), "Thông tin tài khoản", "Có cập nhật liên quan đến tài khoản EcoFood của bạn.", new ActionTarget(ActionKind.PROFILE, null));
    }
    if (event.type() == Type.ACCOUNT) throw new BadRequestException("Invalid notification reference");
    String invoiceId = event.referenceId();
    var invoice = purchases.detail(scope, invoiceId).orElseThrow(() -> new IllegalArgumentException("Notification reference unavailable"));
    if (!event.sourceTenantId().equals(invoice.getTenantId()) || !Objects.equals(event.distributionPartnerId(), invoice.getDistributionPartnerId()))
      throw new BadRequestException("Notification source does not match the owned Invoice");
    String title = event.type() == Type.MARKETING ? "Thông tin từ cửa hàng" : event.type() == Type.TRANSACTIONAL ? "Cập nhật giao dịch" : "Thông tin dịch vụ";
    String message = event.type() == Type.MARKETING ? "Có thông tin tiếp thị từ cửa hàng liên quan đến lần mua hàng đã lưu."
        : "Có cập nhật liên quan đến hóa đơn " + invoice.getInvoiceNumber() + ".";
    return new Resolved(event, customerPortal.tenantId(), title, message, new ActionTarget(ActionKind.INVOICE_DETAIL, event.referenceId()));
  }
  private String fingerprint(Event event) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value : List.of(event.eventId(), event.recipientCustomerId(), event.sourceTenantId(), Objects.toString(event.distributionPartnerId(), ""),
          event.type().name(), event.referenceType().name(), event.referenceId(), event.deliveryChannel().name())) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("Notification hashing unavailable"); }
  }
  private ObjectId objectId(String id) { if (!ObjectId.isValid(id)) throw new BadRequestException("Invalid identifier"); return new ObjectId(id); }
  private Instant now() { return Instant.ofEpochMilli(clock.millis()); }
  private NotificationView view(Notification value) {
    return new NotificationView(value.getIdAsString(), value.getSourceTenantId(), value.getDistributionPartnerId(), value.getType(), value.getTitle(), value.getMessage(),
        value.getReferenceType(), value.getReferenceId(), value.getActionTarget(), value.getDeliveryChannel(), value.getDeliveryStatus(), value.isRead(), value.getReadAt(),
        value.getCreatedAt(), value.getSentAt(), value.getUpdatedAt());
  }
  private record Resolved(Event event, String portalId, String title, String message, ActionTarget target) {}
}
