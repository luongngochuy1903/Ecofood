package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.customer.auth.*;
import com.ecofood.app.customer.repository.*;
import com.ecofood.app.notification.NotificationContracts.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.userACL.entity.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.data.mongodb.core.MongoTemplate;

class NotificationServiceTest {
  static final String CUSTOMER = "000000000000000000000001", PORTAL = "000000000000000000000002", SELLER = "000000000000000000000003", INVOICE = "000000000000000000000004";
  static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
  CustomerIdentityService identities = mock(CustomerIdentityService.class);
  CustomerTenantResolver portal = mock(CustomerTenantResolver.class);
  CustomerPurchaseRepository purchases = mock(CustomerPurchaseRepository.class);
  NotificationPreferenceRepository preferenceRows = mock(NotificationPreferenceRepository.class);
  NotificationRepository rows = mock(NotificationRepository.class);
  MongoTemplate mongo = mock(MongoTemplate.class);
  NotificationPreferenceService preferences = new NotificationPreferenceService(identities, preferenceRows, Clock.fixed(NOW, ZoneOffset.UTC));
  com.ecofood.app.complaint.ComplaintNotificationSource complaintSource = mock(com.ecofood.app.complaint.ComplaintNotificationSource.class);
  NotificationService service = new NotificationService(identities, portal, purchases, preferences, rows, mongo, Clock.fixed(NOW, ZoneOffset.UTC), complaintSource);
  Map<String, Notification> stored = new ConcurrentHashMap<>();
  NotificationPreference preference;
  Invoice invoice;
  CountDownLatch inserts;

  @BeforeEach void setup() {
    var user = UserEntity.builder().partyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(PORTAL)).build())).build(); user.setId(new ObjectId(CUSTOMER));
    when(identities.requireAccount(CUSTOMER)).thenReturn(user);
    when(portal.resolve()).thenReturn(new CustomerTenantResolver.Portal(PORTAL, "Portal", "portal", "client", "https://identity.test/realms/portal"));
    preference = new NotificationPreference(); preference.setCustomerId(CUSTOMER); preference.setCustomerTenantId(PORTAL);
    when(preferenceRows.findByCustomerIdAndCustomerTenantIdAndDeletedNot(CUSTOMER, PORTAL, true)).thenAnswer(call -> Optional.of(preference));
    invoice = new Invoice(); invoice.setId(INVOICE); invoice.setTenantId(SELLER); invoice.setDistributionPartnerId("SHOP-A"); invoice.setInvoiceNumber("INV-A");
    when(purchases.detail(new CustomerIdentityService.CustomerSession(CUSTOMER, PORTAL), INVOICE)).thenReturn(Optional.of(invoice));
    when(rows.findByRecipientCustomerIdAndCustomerTenantIdAndSourceTenantIdAndEventIdAndDeliveryChannel(anyString(), anyString(), anyString(), anyString(), any()))
        .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(2) + ":" + call.getArgument(3) + ":" + call.getArgument(4))));
    when(rows.insert(any(Notification.class))).thenAnswer(call -> {
      var value = (Notification) call.getArgument(0); value.setId(new ObjectId());
      if (inserts != null) { inserts.countDown(); if (!inserts.await(5, TimeUnit.SECONDS)) throw new AssertionError("Missing concurrent insertion"); }
      if (stored.putIfAbsent(key(value), value) != null) throw new DuplicateKeyException("dedup"); return value;
    });
    when(rows.findById(any(ObjectId.class))).thenAnswer(call -> stored.values().stream().filter(row -> row.getId().equals(call.getArgument(0))).findFirst());
  }
  String key(Notification row) { return row.getSourceTenantId() + ":" + row.getEventId() + ":" + row.getDeliveryChannel(); }
  Event event(Type type, Channel channel) { return new Event("event-1", CUSTOMER, SELLER, "SHOP-A", type, ReferenceType.INVOICE, INVOICE, channel); }

  @Test void validOwnedInvoiceCreatesUnreadAvailableInAppNotificationWithSafeAction() {
    var result = service.create(event(Type.TRANSACTIONAL, Channel.IN_APP)); var row = stored.values().iterator().next();
    assertEquals(DeliveryStatus.AVAILABLE, result.deliveryStatus()); assertFalse(result.duplicate()); assertFalse(row.isRead()); assertNull(row.getReadAt()); assertNull(row.getSentAt());
    assertEquals(CUSTOMER, row.getRecipientCustomerId()); assertEquals(PORTAL, row.getCustomerTenantId()); assertEquals(SELLER, row.getSourceTenantId());
    assertEquals(new ActionTarget(ActionKind.INVOICE_DETAIL, INVOICE), row.getActionTarget()); assertTrue(row.getMessage().contains("INV-A"));
    assertEquals(NOW, row.getCreatedAt()); assertEquals(NOW, row.getUpdatedAt());
  }
  @Test void retryIsIdempotentAndAReusedEventWithDifferentContextIsRejected() {
    var first = service.create(event(Type.TRANSACTIONAL, Channel.IN_APP)); var second = service.create(event(Type.TRANSACTIONAL, Channel.IN_APP));
    assertEquals(first.notificationId(), second.notificationId()); assertTrue(second.duplicate()); assertEquals(1, stored.size());
    assertThrows(IllegalStateException.class, () -> service.create(event(Type.SERVICE, Channel.IN_APP))); verify(rows, times(1)).insert(any(Notification.class));
  }
  @Test void concurrentRetriesProduceOneCanonicalDocument() throws Exception {
    inserts = new CountDownLatch(2); var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> service.create(event(Type.TRANSACTIONAL, Channel.IN_APP)));
      var b = pool.submit(() -> service.create(event(Type.TRANSACTIONAL, Channel.IN_APP)));
      var first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS);
      assertEquals(first.notificationId(), second.notificationId()); assertNotEquals(first.duplicate(), second.duplicate()); assertEquals(1, stored.size());
    } finally { pool.shutdownNow(); }
  }
  @Test void channelsHaveSeparateDedupKeysAndEmailRemainsPendingWithoutPretendingToSend() {
    service.create(event(Type.TRANSACTIONAL, Channel.IN_APP)); var email = service.create(event(Type.TRANSACTIONAL, Channel.EMAIL));
    assertEquals(2, stored.size()); assertEquals(DeliveryStatus.PENDING, email.deliveryStatus()); assertNull(stored.get(SELLER + ":event-1:EMAIL").getSentAt());
    assertTrue(service.prepareDelivery(email.notificationId()).allowed());
  }
  @Test void marketingDefaultsToSuppressedAndGrantDoesNotRetroactivelyResendAnOldEvent() {
    var first = service.create(event(Type.MARKETING, Channel.IN_APP)); assertEquals(DeliveryStatus.SUPPRESSED, first.deliveryStatus());
    preference.setMarketingConsent(true); preference.setMarketingInAppEnabled(true);
    var second = service.create(event(Type.MARKETING, Channel.IN_APP)); assertTrue(second.duplicate()); assertEquals(DeliveryStatus.SUPPRESSED, second.deliveryStatus());
    assertEquals(1, stored.size());
  }
  @Test void deliveryPreparationRechecksWithdrawnMarketingConsentAndDoesNotMarkEmailSent() {
    preference.setMarketingConsent(true); preference.setMarketingEmailEnabled(true);
    var created = service.create(event(Type.MARKETING, Channel.EMAIL)); assertTrue(service.prepareDelivery(created.notificationId()).allowed());
    preference.setMarketingConsent(false); preference.setMarketingEmailEnabled(false);
    assertFalse(service.prepareDelivery(created.notificationId()).allowed());
    assertEquals(DeliveryStatus.PENDING, stored.values().iterator().next().getDeliveryStatus()); verifyNoInteractions(mongo);
  }
  @Test void essentialAccountMessagesRemainEnabledWithoutAllowingMarketingToMasqueradeAsAccount() {
    preference.setTransactionalEmailEnabled(false); preference.setServiceInAppEnabled(false);
    var account = new Event("account-1", CUSTOMER, PORTAL, null, Type.ACCOUNT, ReferenceType.ACCOUNT, CUSTOMER, Channel.EMAIL);
    assertEquals(DeliveryStatus.PENDING, service.create(account).deliveryStatus());
    assertThrows(BadRequestException.class, () -> service.create(new Event("account-2", CUSTOMER, PORTAL, null, Type.MARKETING, ReferenceType.ACCOUNT, CUSTOMER, Channel.EMAIL)));
    assertEquals(DeliveryStatus.SUPPRESSED, service.create(event(Type.SERVICE, Channel.IN_APP)).deliveryStatus());
  }
  @Test void unknownOrUnownedInvoiceWrongSellerAndWrongPartnerCannotCreateNotification() {
    assertThrows(BadRequestException.class, () -> service.create(new Event("event-1", CUSTOMER, PORTAL, "SHOP-A", Type.SERVICE, ReferenceType.INVOICE, INVOICE, Channel.IN_APP)));
    assertThrows(BadRequestException.class, () -> service.create(new Event("event-1", CUSTOMER, SELLER, "OTHER", Type.SERVICE, ReferenceType.INVOICE, INVOICE, Channel.IN_APP)));
    when(purchases.detail(any(), eq(INVOICE))).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.create(event(Type.SERVICE, Channel.IN_APP))); verify(rows, never()).insert(any(Notification.class));
  }
  @Test void invalidEventAndInvalidRecipientAreRejectedBeforePersistence() {
    assertThrows(BadRequestException.class, () -> service.create(new Event("bad/event", CUSTOMER, SELLER, "SHOP-A", Type.SERVICE, ReferenceType.INVOICE, INVOICE, Channel.IN_APP)));
    doThrow(new IllegalArgumentException("Disabled customer")).when(identities).requireAccount(CUSTOMER);
    assertThrows(IllegalArgumentException.class, () -> service.create(event(Type.SERVICE, Channel.IN_APP))); verifyNoInteractions(rows);
  }
  @Test void databaseFailureDoesNotReturnSuccessfulCreation() {
    doThrow(new DataAccessResourceFailureException("Unavailable")).when(rows).insert(any(Notification.class));
    assertThrows(DataAccessResourceFailureException.class, () -> service.create(event(Type.TRANSACTIONAL, Channel.IN_APP)));
  }
  @Test void legacyFeedbackCannotCreateNewNotificationsOrDeliverHistoricalEmail() {
    assertThrows(BadRequestException.class, () -> service.create(new Event("legacy", CUSTOMER, SELLER, "SHOP-A", Type.SERVICE, ReferenceType.FEEDBACK, INVOICE, Channel.IN_APP)));
    var legacy = new Notification(); legacy.setId(new ObjectId()); legacy.setReferenceType(ReferenceType.FEEDBACK);
    legacy.setDeliveryChannel(Channel.EMAIL); legacy.setDeliveryStatus(DeliveryStatus.PENDING);
    when(rows.findById(legacy.getId())).thenReturn(Optional.of(legacy));
    assertEquals(new DeliveryDecision(false, "REFERENCE_RETIRED"), service.prepareDelivery(legacy.getIdAsString()));
    verify(rows, never()).insert(any(Notification.class)); verifyNoInteractions(purchases);
  }
  @Test void complaintSourceIsRevalidatedWithoutDependingOnInvoiceClaimAndCannotOverrideRecipient() {
    String complaint = "000000000000000000000031", id = complaint + ":1:COMPLAINT";
    var notice = new com.ecofood.app.complaint.ComplaintNotificationSource.Notice(id, complaint, CUSTOMER, PORTAL, SELLER, "SHOP-A", "Đã tiếp nhận khiếu nại", "Nội dung an toàn");
    when(complaintSource.forNotification(id, complaint)).thenReturn(notice);
    preference.setServiceInAppEnabled(false); preference.setMarketingConsent(false);
    var result = service.create(notice.notification()); assertEquals(DeliveryStatus.AVAILABLE, result.deliveryStatus());
    var value = stored.values().iterator().next(); assertEquals(new ActionTarget(ActionKind.COMPLAINT_DETAIL, complaint), value.getActionTarget());
    assertTrue(service.create(notice.notification()).duplicate()); assertEquals(1, stored.size()); verifyNoInteractions(purchases);
    assertThrows(BadRequestException.class, () -> service.create(new Event(id, CUSTOMER, PORTAL, "SHOP-A", Type.COMPLAINT, ReferenceType.COMPLAINT, complaint, Channel.IN_APP)));
    assertThrows(BadRequestException.class, () -> service.create(new Event(id, CUSTOMER, SELLER, "SHOP-A", Type.COMPLAINT, ReferenceType.COMPLAINT, complaint, Channel.EMAIL)));
    assertThrows(BadRequestException.class, () -> service.create(new Event(id, CUSTOMER, SELLER, "SHOP-A", Type.SERVICE, ReferenceType.COMPLAINT, complaint, Channel.IN_APP)));
    when(complaintSource.forNotification(id, complaint)).thenReturn(new com.ecofood.app.complaint.ComplaintNotificationSource.Notice(id, complaint,
        "000000000000000000000099", PORTAL, SELLER, "SHOP-A", "Private", "Private"));
    assertThrows(BadRequestException.class, () -> service.create(notice.notification()));
    assertEquals(1, stored.size());
  }
}
