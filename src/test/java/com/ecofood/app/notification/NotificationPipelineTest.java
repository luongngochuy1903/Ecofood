package com.ecofood.app.notification;

import static com.ecofood.app.notification.NotificationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.query.*;

class NotificationPipelineTest {
  AtomicNotificationMongo db;
  NotificationRecipients recipients;
  NotificationProcessor processor;
  NotificationDelivery delivery;
  NotificationEmailSender sender;
  @BeforeEach void setup() throws Exception {
    db = new AtomicNotificationMongo(); recipients = mock(NotificationRecipients.class); sender = mock(NotificationEmailSender.class);
    processor = new NotificationProcessor(db.mongo, List.of(new InvoiceNotificationSource(db.mongo)), recipients, new NotificationTemplate(), settings());
    delivery = new NotificationDelivery(db.mongo, sender, recipients, settings());
    db.insert(event()); db.insert(invoice());
    when(recipients.resolve(any(), any())).thenReturn(List.of(new NotificationRecipients.Recipient("customer@example.org", USER)));
    when(recipients.stillAllowed(any())).thenReturn(true);
  }
  @Test void enabledRuleCreatesOneNotificationThenEmailSentWithoutChangingInvoiceOrMetrics() {
    db.insert(rule());
    db.raw(Submission.class, "event").put("actualMetrics", new Document("state", "SUCCEEDED").append("projectionRevision", 7));
    var before = new Document(db.raw(Invoice.class, "invoice"));
    processor.poll(); delivery.poll();
    assertEquals(1, db.rows(Notification.class).size());
    var row = db.find(new Query(), Notification.class).get(0);
    assertEquals("SENT", row.getStatus()); assertNotNull(row.getSentAt()); assertEquals("Invoice INV1", row.getSubject()); assertEquals(2, row.getRuleRevision());
    assertEquals(before, db.raw(Invoice.class, "invoice"));
    assertEquals("SUCCEEDED", ((Document) db.raw(Submission.class, "event").get("actualMetrics")).getString("state"));
    verify(sender, times(1)).send(any());
  }
  @Test void noRuleDoesNotSendAndLeavesTrace() {
    processor.poll(); delivery.poll(); assertTrue(db.rows(Notification.class).isEmpty()); verifyNoInteractions(sender);
    assertEquals("NO_RULE", AtomicNotificationMongo.get(db.raw(Submission.class, "event"), "notification.result"));
  }
  @Test void disabledRuleDoesNotSend() {
    var rule = rule(); rule.setEnabled(false); db.insert(rule); processor.poll(); assertTrue(db.rows(Notification.class).isEmpty());
  }
  @Test void disabledWorkflowDoesNotCreateSecondSendingPath() {
    db.insert(rule()); db.raw(Invoice.class, "invoice").put("postWorkflowResults", new Document());
    processor.poll(); delivery.poll(); assertTrue(db.rows(Notification.class).isEmpty()); verifyNoInteractions(sender);
    assertEquals("WORKFLOW_DISABLED", AtomicNotificationMongo.get(db.raw(Submission.class, "event"), "notification.result"));
  }
  @Test void missingRecipientNeverFallsBackToSubmitter() {
    db.insert(rule()); when(recipients.resolve(any(), any())).thenReturn(List.of()); processor.poll(); assertTrue(db.rows(Notification.class).isEmpty());
    assertEquals("NO_VALID_RECIPIENT", AtomicNotificationMongo.get(db.raw(Submission.class, "event"), "notification.result"));
  }
  @Test void replayAfterDeliveryAndBeforeGenerationAckKeepsSentAndDoesNotSendAgain() {
    db.insert(rule()); processor.poll(); delivery.poll();
    Document state = (Document) db.raw(Submission.class, "event").get("notification");
    state.put("state", "RETRY"); state.put("nextAttemptAt", Date.from(Instant.now().minusSeconds(1)));
    processor.poll(); delivery.poll(); assertEquals(1, db.rows(Notification.class).size()); verify(sender, times(1)).send(any());
    assertEquals("DONE", state.getString("state"));
  }
  @Test void frozenPlanSurvivesFailureBeforeInsertAndRuleEdits() {
    db.insert(rule());
    doThrow(new DataAccessResourceFailureException("offline")).doAnswer(a -> db.insert(a.getArgument(0))).when(db.mongo).insert(any(Notification.class));
    processor.poll(); assertTrue(db.rows(Notification.class).isEmpty());
    assertNotNull(AtomicNotificationMongo.get(db.raw(Submission.class, "event"), "notification.plan"));
    db.raw(NotificationRule.class, "rule").put("content", "Changed later");
    ((Document) db.raw(Submission.class, "event").get("notification")).put("nextAttemptAt", Date.from(Instant.now().minusSeconds(1)));
    processor.poll();
    assertEquals("Recorded INV1", db.find(new Query(), Notification.class).get(0).getContent());
  }
  @Test void twoWorkersCannotClaimSameSubmissionOrNotification() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch ready = new CountDownLatch(1);
      Callable<SubmissionNotificationState> claim = () -> { ready.await(); return processor.claim(Instant.now()); };
      var first = pool.submit(claim); var second = pool.submit(claim); ready.countDown();
      assertNotEquals(first.get() == null, second.get() == null);
      db.insert(NotificationFixtures.delivery()); CountDownLatch start = new CountDownLatch(1);
      Callable<Notification> take = () -> { start.await(); return delivery.claim(Instant.now()); };
      var a = pool.submit(take); var b = pool.submit(take); start.countDown(); assertNotEquals(a.get() == null, b.get() == null);
    } finally { pool.shutdownNow(); }
  }
  @Test void staleGenerationLeaseCannotFreezeOrCompleteWork() {
    db.insert(rule()); var stale = processor.claim(Instant.now());
    ((Document) db.raw(Submission.class, "event").get("notification")).put("leaseUntil", Date.from(Instant.now().minusSeconds(1)));
    var next = processor.claim(Instant.now()); processor.process(stale); assertTrue(db.rows(Notification.class).isEmpty());
    processor.process(next); assertEquals(1, db.rows(Notification.class).size());
  }
  @Test void temporaryFailureBacksOffAndStopsAtLimit() {
    db.insert(NotificationFixtures.delivery());
    doThrow(new NotificationEmailSender.DeliveryFailure("SMTP_CONNECT_FAILED", true, false)).when(sender).send(any());
    for (int count = 1; count <= 3; count++) {
      delivery.poll(); var row = db.find(new Query(), Notification.class).get(0);
      assertEquals(count < 3 ? "RETRY_PENDING" : "FAILED", row.getStatus()); assertEquals(count, row.getRetryCount());
      assertTrue(row.getNextAttemptAt().isAfter(Instant.now().plusSeconds(50)));
      db.raw(Notification.class, "delivery").put("nextAttemptAt", Date.from(Instant.now().minusSeconds(1)));
    }
    delivery.poll(); verify(sender, times(3)).send(any());
    assertEquals("PAID", db.find(new Query(), Invoice.class).get(0).getPaymentStatus());
  }
  @Test void crashDuringSmtpRequiresExplicitRetryNeverAutomaticResend() {
    db.insert(NotificationFixtures.delivery()); var claimed = delivery.claim(Instant.now());
    db.raw(Notification.class, "delivery").put("leaseUntil", Date.from(Instant.now().minusSeconds(1)));
    delivery.deliver(claimed); delivery.poll();
    var row = db.find(new Query(), Notification.class).get(0);
    assertEquals("FAILED", row.getStatus()); assertTrue(row.isDeliveryUncertain()); verifyNoInteractions(sender);
  }
  @Test void failureToPersistSentLeavesUncertainOutcomeAndDoesNotResend() {
    db.insert(NotificationFixtures.delivery()); var work = delivery.claim(Instant.now());
    doThrow(new DataAccessResourceFailureException("offline")).when(db.mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(Notification.class));
    assertThrows(DataAccessResourceFailureException.class, () -> delivery.deliver(work));
    assertEquals("PROCESSING", db.raw(Notification.class, "delivery").getString("status"));
    verify(sender, times(1)).send(any());
  }
  @Test void recipientRevocationFailsWithoutCallingProvider() {
    db.insert(NotificationFixtures.delivery()); when(recipients.stillAllowed(any())).thenReturn(false); delivery.poll();
    assertEquals("RECIPIENT_SCOPE_REVOKED", db.raw(Notification.class, "delivery").getString("lastError")); verifyNoInteractions(sender);
  }
  @Test void crossTenantInvoiceIsRejected() {
    db.insert(rule()); db.raw(Invoice.class, "invoice").put("tenantId", OTHER); processor.poll();
    assertTrue(db.rows(Notification.class).isEmpty()); assertEquals("RETRY", AtomicNotificationMongo.get(db.raw(Submission.class, "event"), "notification.state"));
  }
  @Test void sourceEnvelopeStillEqualsTask3AAfterAddingConsumerState() {
    var before = db.converter.read(Submission.class, db.raw(Submission.class, "event"));
    processor.poll(); assertEquals(before, db.converter.read(Submission.class, db.raw(Submission.class, "event")));
  }
}
