package com.ecofood.app.notification;

/** Provider implementations must use notificationId as their durable idempotency key. */
public interface EmailDeliveryAdapter {
  boolean enabled();
  Result deliver(Message message);
  record Message(String notificationId, String customerId, String verifiedEmail, String subject, String text) {}
  record Result(boolean captured, String provider, String messageId) {}
}
