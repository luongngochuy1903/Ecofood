package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.notification.NotificationContracts.*;
import com.ecofood.app.userACL.entity.UserEntity;
import java.time.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class NotificationEmailWorkerTest {
  final MongoTemplate mongo = mock(MongoTemplate.class);
  final NotificationService notifications = mock(NotificationService.class);
  final CustomerIdentityService identities = mock(CustomerIdentityService.class);
  final EmailDeliveryAdapter adapter = mock(EmailDeliveryAdapter.class);
  final Instant now = Instant.parse("2026-11-16T01:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  final NotificationEmailWorker worker = new NotificationEmailWorker(mongo, notifications, identities, adapter, clock);
  Notification row;

  @BeforeEach void setup() {
    row = new Notification(); row.setId(new ObjectId()); row.setRecipientCustomerId(new ObjectId().toHexString());
    row.setDeliveryStatus(DeliveryStatus.PENDING); row.setDeliveryChannel(Channel.EMAIL); row.setType(Type.ACCOUNT); row.setDeliveryAttempts(1);
    when(adapter.enabled()).thenReturn(true);
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Notification.class))).thenReturn(row);
    when(notifications.prepareDelivery(row.getIdAsString())).thenReturn(new DeliveryDecision(true, "AUTHORIZED"));
    var customer = UserEntity.builder().email("verified@example.test").build();
    when(identities.requireAccount(row.getRecipientCustomerId())).thenReturn(customer);
    when(adapter.deliver(any())).thenReturn(new EmailDeliveryAdapter.Result(false, "TEST_PROVIDER", "accepted-1"));
  }
  Document update() {
    var captor = ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateFirst(any(Query.class), captor.capture(), eq(Notification.class));
    return captor.getValue().getUpdateObject();
  }
  @Test void disabledProviderDoesNotClaimOrPretendToSend() {
    when(adapter.enabled()).thenReturn(false); assertFalse(worker.deliverOne()); verifyNoInteractions(mongo, notifications, identities); verify(adapter, never()).deliver(any());
  }
  @Test void verifiedRecipientAndSafeTemplateAreUsedAndSentIsSeparateFromRead() {
    assertTrue(worker.deliverOne());
    var payload = ArgumentCaptor.forClass(EmailDeliveryAdapter.Message.class); verify(adapter).deliver(payload.capture());
    assertEquals("verified@example.test", payload.getValue().verifiedEmail()); assertEquals(row.getIdAsString(), payload.getValue().notificationId());
    assertEquals(row.getRecipientCustomerId(), payload.getValue().customerId());
    assertFalse(payload.getValue().text().contains("http")); assertFalse(payload.getValue().text().contains("token"));
    var set = update().get("$set", Document.class); assertEquals(DeliveryStatus.SENT, set.get("deliveryStatus")); assertEquals(now, set.get("sentAt"));
    assertFalse(set.containsKey("read")); assertFalse(set.containsKey("readAt")); verify(notifications, times(2)).prepareDelivery(row.getIdAsString());
  }
  @Test void developmentCaptureIsNeverReportedAsSent() {
    when(adapter.deliver(any())).thenReturn(new EmailDeliveryAdapter.Result(true, "DEVELOPMENT_CAPTURE", row.getIdAsString()));
    worker.deliverOne(); var set = update().get("$set", Document.class);
    assertEquals(DeliveryStatus.CAPTURED, set.get("deliveryStatus")); assertFalse(set.containsKey("sentAt"));
  }
  @Test void withdrawnConsentSuppressesWithoutCallingProvider() {
    when(notifications.prepareDelivery(anyString())).thenReturn(new DeliveryDecision(false, "PREFERENCE_DISABLED"));
    worker.deliverOne(); verify(adapter, never()).deliver(any()); verifyNoInteractions(identities);
    assertEquals(DeliveryStatus.SUPPRESSED, update().get("$set", Document.class).get("deliveryStatus"));
  }
  @Test void withdrawalBetweenIdentityCheckAndSendIsRechecked() {
    when(notifications.prepareDelivery(anyString())).thenReturn(new DeliveryDecision(true, "AUTHORIZED"), new DeliveryDecision(false, "PREFERENCE_DISABLED"));
    worker.deliverOne(); verify(adapter, never()).deliver(any()); assertEquals(DeliveryStatus.SUPPRESSED, update().get("$set", Document.class).get("deliveryStatus"));
  }
  @Test void unverifiedOrDisabledIdentityIsNotSent() {
    when(identities.requireAccount(anyString())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    worker.deliverOne(); verify(adapter, never()).deliver(any());
    assertEquals("RECIPIENT_OR_REFERENCE_UNAVAILABLE", update().get("$set", Document.class).get("lastDeliveryError"));
  }
  @Test void temporaryProviderFailureRetriesWithSanitizedErrorAndNoSentTimestamp() {
    when(adapter.deliver(any())).thenThrow(new IllegalStateException("secret provider body must not be stored"));
    worker.deliverOne(); var set = update().get("$set", Document.class);
    assertEquals(DeliveryStatus.PENDING, set.get("deliveryStatus")); assertEquals("EMAIL_DELIVERY_FAILED", set.get("lastDeliveryError"));
    assertEquals(now.plusSeconds(10), set.get("nextDeliveryAttemptAt")); assertFalse(set.containsKey("sentAt"));
  }
  @Test void keycloakOutageRetriesInsteadOfTreatingIdentityAsInvalid() {
    when(notifications.prepareDelivery(anyString())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
    worker.deliverOne(); verify(adapter, never()).deliver(any()); assertEquals(DeliveryStatus.PENDING, update().get("$set", Document.class).get("deliveryStatus"));
  }
  @Test void boundedFailuresAreRecordedAsFailed() {
    row.setDeliveryAttempts(10); when(adapter.deliver(any())).thenThrow(new IllegalStateException("temporary"));
    worker.deliverOne(); assertEquals(DeliveryStatus.FAILED, update().get("$set", Document.class).get("deliveryStatus"));
  }
  @Test void noLeaseMeansNoDelivery() {
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Notification.class))).thenReturn(null);
    assertFalse(worker.deliverOne()); verify(adapter, never()).deliver(any()); verifyNoInteractions(notifications);
  }
  @Test void captureAdapterIsDisabledByDefaultAndRejectsUnknownModes() {
    var config = new NotificationEmailAdapters(); var disabled = config.notificationEmailAdapter(mongo, clock, "disabled");
    assertFalse(disabled.enabled()); assertThrows(IllegalStateException.class, () -> disabled.deliver(null));
    assertThrows(IllegalArgumentException.class, () -> config.notificationEmailAdapter(mongo, clock, "smtp"));
    verifyNoInteractions(mongo);
  }
}
