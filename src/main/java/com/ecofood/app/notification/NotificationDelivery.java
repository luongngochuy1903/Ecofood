package com.ecofood.app.notification;

import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationDelivery {
  private final MongoTemplate mongo;
  private final NotificationEmailSender sender;
  private final NotificationRecipients recipients;
  private final NotificationSettings settings;
  @Scheduled(fixedDelayString = "${ecofood.notification.delivery-poll-ms:5000}", initialDelayString = "${ecofood.notification.initial-delay-ms:20000}")
  public void poll() {
    if (!settings.deliveryEnabled) return;
    try {
      // Never automatically resend an expired SMTP attempt with an unknown external outcome.
      mongo.updateMulti(Query.query(Criteria.where("status").is("PROCESSING").and("leaseUntil").lte(Instant.now())),
          new Update().set("status", "FAILED").set("deliveryUncertain", true).set("lastError", "SMTP_OUTCOME_UNCERTAIN")
            .unset("leaseToken").unset("leaseUntil"), Notification.class);
      for (int i = 0; i < 20; i++) {
        Notification work = claim(Instant.now()); if (work == null) return; deliver(work);
      }
    } catch (RuntimeException unavailable) { /* Persisted queue survives outage; next poll resumes. */ }
  }
  Notification claim(Instant now) {
    return mongo.findAndModify(Query.query(Criteria.where("status").in("PENDING", "RETRY_PENDING")
        .and("nextAttemptAt").lte(now).and("retryCount").lt(settings.maxAttempts)),
        new Update().set("status", "PROCESSING").set("leaseToken", UUID.randomUUID().toString())
            .set("leaseUntil", now.plusSeconds(300)).inc("retryCount", 1).inc("totalAttempts", 1),
        FindAndModifyOptions.options().returnNew(true), Notification.class);
  }
  private Query owned(Notification work) {
    return Query.query(Criteria.where("_id").is(work.getId()).and("tenantId").is(work.getTenantId())
        .and("status").is("PROCESSING").and("leaseToken").is(work.getLeaseToken()).and("leaseUntil").gt(Instant.now()));
  }
  void deliver(Notification work) {
    // Check fencing immediately before invoking the external provider.
    if (!mongo.exists(owned(work), Notification.class)) return;
    try {
      if (!NotificationSettings.validEmail(work.getSendFrom()) || !NotificationSettings.validEmail(work.getSendTo())) throw new NotificationEmailSender.DeliveryFailure("EMAIL_INVALID", false, false);
      if (!recipients.stillAllowed(work)) throw new NotificationEmailSender.DeliveryFailure("RECIPIENT_SCOPE_REVOKED", false, false);
      sender.send(work);
    } catch (NotificationEmailSender.DeliveryFailure failure) {
      boolean retry = failure.retryable && !failure.uncertain && work.getRetryCount() < settings.maxAttempts;
      mongo.updateFirst(owned(work), new Update().set("status", retry ? "RETRY_PENDING" : "FAILED")
          .set("nextAttemptAt", Instant.now().plusSeconds(backoff(work.getRetryCount())))
          .set("deliveryUncertain", failure.uncertain).set("lastError", failure.code).unset("leaseToken").unset("leaseUntil"), Notification.class);
      return;
    }
    // If this write fails, retain PROCESSING; lease recovery marks the outcome uncertain, never blindly resends.
    mongo.updateFirst(owned(work), new Update().set("status", "SENT").set("sentAt", Instant.now())
        .set("deliveryUncertain", false).unset("lastError").unset("leaseToken").unset("leaseUntil").unset("nextAttemptAt"), Notification.class);
  }
  static long backoff(int attempt) { return Math.min(3600, 60L << Math.min(6, Math.max(0, attempt - 1))); }
}
