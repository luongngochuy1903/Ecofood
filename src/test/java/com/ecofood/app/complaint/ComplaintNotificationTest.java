package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ecofood.app.complaint.ComplaintCoreFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.notification.NotificationService;
import com.ecofood.app.notification.NotificationContracts.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ComplaintNotificationTest {
  ComplaintCoreFixture f = new ComplaintCoreFixture();
  ComplaintRepository complaints = mock(ComplaintRepository.class);
  ComplaintEventRepository history = mock(ComplaintEventRepository.class);
  ComplaintOutboxRepository outbox = mock(ComplaintOutboxRepository.class);
  NotificationService notifications = mock(NotificationService.class);
  ComplaintNotificationSource source = new ComplaintNotificationSource(complaints, history, outbox);
  ComplaintNotificationWorker worker = new ComplaintNotificationWorker(outbox, source, notifications);
  Map<String, DomainEvent> pending = new HashMap<>();
  Complaint row;

  @BeforeEach void setup() {
    row = f.row(); row.setIdentityVerified(true); row.setInvoiceTenantId(SELLER); row.setDistributionPartnerId("SHOP-OPAQUE");
    when(complaints.findCommitted(CASE)).thenReturn(Optional.of(row));
    when(outbox.findEvent(anyString())).thenAnswer(call -> Optional.ofNullable(pending.get(call.getArgument(0))));
  }
  DomainEvent event(EventType type, Status to) {
    var original = f.event(row, Visibility.INTERNAL);
    var e = new ComplaintEvent(original.eventId(), CASE, 1, type, ActorType.EMPLOYEE, HANDLER, Status.IN_PROGRESS, to,
        "private-queue", "private-next", "SECRET INTERNAL NOTE", Visibility.INTERNAL, f.clock.instant(), "trace", "key", "hash", original.result());
    when(history.findById(e.eventId())).thenReturn(Optional.of(e));
    for (var emitted : ComplaintProjectionService.outboxTypes(e)) {
      var domain = new DomainEvent(e.eventId() + ":" + emitted, CASE, 1, emitted, e.occurredAt(), e.correlationId()); pending.put(domain.eventId(), domain);
    }
    return pending.get(e.eventId() + ":" + type);
  }
  @Test void everySupportedEventHasSafeCustomerContentAndIndependentSellerScope() {
    for (EventType type : EventType.values()) {
      pending.clear(); var e = event(type, type == EventType.COMPLAINT_CLOSED ? Status.CLOSED : Status.IN_PROGRESS);
      var notice = source.forEvent(e).orElseThrow();
      assertEquals(CUSTOMER, notice.customerId()); assertEquals(PORTAL, notice.homeTenantId()); assertEquals(SELLER, notice.sourceTenantId());
      assertEquals("SHOP-OPAQUE", notice.distributionPartnerId()); assertEquals(CASE, notice.notification().referenceId());
      assertEquals(Type.COMPLAINT, notice.notification().type()); assertEquals(Channel.IN_APP, notice.notification().deliveryChannel());
      assertFalse(notice.toString().contains("SECRET")); assertFalse(notice.toString().contains("private-queue"));
      assertFalse(notice.toString().contains(HANDLER)); assertEquals(notice, source.forNotification(notice.eventId(), CASE));
    }
  }
  @Test void statusAndSpecificEventsFromOneCommandHaveExactlyTheSameDeduplicationContext() {
    for (var status : List.of(Status.RESOLVED, Status.CLOSED, Status.WAITING_REPORTER)) {
      pending.clear(); var type = status == Status.RESOLVED ? EventType.COMPLAINT_RESOLVED : status == Status.CLOSED ? EventType.COMPLAINT_CLOSED : EventType.COMPLAINT_STATUS_CHANGED;
      var original = event(type, status); var expected = source.forEvent(original).orElseThrow();
      assertEquals(2, pending.size());
      for (var e : pending.values()) assertEquals(expected.notification(), source.forEvent(e).orElseThrow().notification());
      if (status == Status.WAITING_REPORTER) assertTrue(expected.title().contains("bổ sung"));
    }
  }
  @Test void guestUnverifiedAndInternalCasesCannotProduceCustomerNotifications() {
    var e = event(EventType.COMPLAINT_SUBMITTED, Status.SUBMITTED);
    row.setReporterType(ActorType.GUEST); assertTrue(source.forEvent(e).isEmpty());
    row.setReporterType(ActorType.CUSTOMER); row.setIdentityVerified(false); assertTrue(source.forEvent(e).isEmpty());
    row.setIdentityVerified(true); row.setKind(Kind.INTERNAL); assertTrue(source.forEvent(e).isEmpty());
    row.setKind(Kind.PLATFORM); var notice = source.forEvent(e).orElseThrow(); assertEquals(PORTAL, notice.sourceTenantId()); assertNull(notice.distributionPartnerId());
  }
  @Test void missingOrForgedOutboxHistoryAndReferencesFailClosed() {
    var e = event(EventType.COMPLAINT_SUBMITTED, Status.SUBMITTED);
    assertThrows(IllegalArgumentException.class, () -> source.forNotification(CASE + ":2:COMPLAINT", CASE));
    assertThrows(IllegalArgumentException.class, () -> source.forNotification(CASE + ":1:COMPLAINT", INVOICE));
    var forged = new DomainEvent(e.eventId(), e.complaintId(), e.sequence(), EventType.COMPLAINT_CLOSED, e.occurredAt(), e.correlationId());
    assertThrows(IllegalArgumentException.class, () -> source.forEvent(forged));
    pending.clear(); assertThrows(IllegalArgumentException.class, () -> source.forEvent(e));
    assertThrows(IllegalArgumentException.class, () -> source.forNotification(CASE + ":1:COMPLAINT", CASE));
    verifyNoInteractions(notifications);
  }
  @Test void deliveryFailureRetriesWithoutAcknowledgingOrChangingTheComplaint() {
    var e = event(EventType.COMPLAINT_SUBMITTED, Status.SUBMITTED); var lease = new OutboxLease(e, "consumer", "lease");
    when(outbox.claimNext(anyString())).thenReturn(Optional.of(lease), Optional.empty());
    doThrow(new IllegalStateException("private provider diagnostics")).when(notifications).create(any());
    worker.poll(); verify(outbox).retry(lease); verify(outbox, never()).acknowledge(any());
    verify(complaints, never()).insert(any()); assertEquals(Status.SUBMITTED, row.getStatus());
  }
  @Test void guestOutboxIsAcknowledgedWithoutCreatingAnyCustomerRecord() {
    row.setReporterType(ActorType.GUEST); var e = event(EventType.COMPLAINT_SUBMITTED, Status.SUBMITTED);
    var lease = new OutboxLease(e, "consumer", "lease"); when(outbox.claimNext(anyString())).thenReturn(Optional.of(lease), Optional.empty());
    worker.poll(); verify(outbox).acknowledge(lease); verifyNoInteractions(notifications);
  }
  @Test void pollingIsBoundedAndCannotBecomeAnInfiniteRetryLoop() {
    var e = event(EventType.COMPLAINT_SUBMITTED, Status.SUBMITTED); var lease = new OutboxLease(e, "consumer", "lease");
    when(outbox.claimNext(anyString())).thenReturn(Optional.of(lease));
    worker.poll(); verify(outbox, times(25)).claimNext(anyString()); verify(notifications, times(25)).create(any());
  }
}
