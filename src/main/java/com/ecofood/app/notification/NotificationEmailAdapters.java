package com.ecofood.app.notification;

import java.time.Clock;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration
public class NotificationEmailAdapters {
  @Bean @ConditionalOnMissingBean(EmailDeliveryAdapter.class)
  public EmailDeliveryAdapter notificationEmailAdapter(MongoTemplate mongo, Clock clock,
      @Value("${ecofood.notifications.email-mode:disabled}") String mode) {
    if (!mode.equals("disabled") && !mode.equals("capture")) throw new IllegalArgumentException("Notification email-mode must be disabled or capture");
    return new EmailDeliveryAdapter() {
      public boolean enabled() { return mode.equals("capture"); }
      public Result deliver(Message message) {
        if (!enabled()) throw new IllegalStateException("Email provider not configured");
        // Development sink only: no SMTP, no recipient email/credential/token retained or logged.
        var capture = new Document("_id", message.notificationId()).append("customerId", message.customerId())
            .append("subject", message.subject()).append("text", message.text()).append("capturedAt", clock.instant())
            .append("provider", "DEVELOPMENT_CAPTURE");
        try { mongo.insert(capture, "notification_email_capture"); }
        catch (DuplicateKeyException duplicate) { /* Durable provider idempotency key already accepted. */ }
        return new Result(true, "DEVELOPMENT_CAPTURE", message.notificationId());
      }
    };
  }
}
