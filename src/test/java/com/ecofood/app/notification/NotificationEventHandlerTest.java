package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.notification.NotificationContracts.*;
import java.time.Instant;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class NotificationEventHandlerTest {
  final String customer = new ObjectId().toHexString(), portal = new ObjectId().toHexString();
  @Test void handlerPersistsInAppBeforeEmailAndReusesStableEventIdOnRetry() {
    var notifications = mock(NotificationService.class); var handler = new NotificationEventHandler(notifications);
    var event = NotificationOutboxEvent.account(NotificationOutboxEvent.Kind.PROFILE_UPDATED, customer, portal, Instant.now());
    handler.handle(event); handler.handle(event);
    var order = inOrder(notifications); order.verify(notifications).create(event.channel(Channel.IN_APP)); order.verify(notifications).create(event.channel(Channel.EMAIL));
    order.verify(notifications).create(event.channel(Channel.IN_APP)); order.verify(notifications).create(event.channel(Channel.EMAIL));
    assertEquals(event.eventId(), event.channel(Channel.EMAIL).eventId());
  }
  @Test void failedCreationIsNotAcknowledgedAsSuccessful() {
    var notifications = mock(NotificationService.class); var handler = new NotificationEventHandler(notifications);
    var event = NotificationOutboxEvent.account(NotificationOutboxEvent.Kind.CUSTOMER_REGISTERED, customer, portal, Instant.now());
    when(notifications.create(event.channel(Channel.IN_APP))).thenThrow(new IllegalStateException("DB failure"));
    assertThrows(IllegalStateException.class, () -> handler.handle(event)); verify(notifications, never()).create(event.channel(Channel.EMAIL));
  }
  @Test void foreignSourceAndSpoofedEventKindAreRejected() {
    var event = NotificationOutboxEvent.account(NotificationOutboxEvent.Kind.PROFILE_UPDATED, customer, portal, Instant.now());
    var owner = new Document("_id", new ObjectId()).append("user_identifier", new ObjectId(customer)).append("tenant_identifier", new ObjectId(portal));
    NotificationOutboxWorker.validateSource("customer_profile", owner, event);
    assertThrows(SecurityException.class, () -> NotificationOutboxWorker.validateSource("app_user", owner, event));
    owner.put("user_identifier", new ObjectId());
    assertThrows(SecurityException.class, () -> NotificationOutboxWorker.validateSource("customer_profile", owner, event));
  }
  @Test void retryBackoffIsBounded() {
    assertEquals(5, NotificationOutboxWorker.backoff(0)); assertEquals(10, NotificationOutboxWorker.backoff(1)); assertEquals(3600, NotificationOutboxWorker.backoff(999));
  }
}
