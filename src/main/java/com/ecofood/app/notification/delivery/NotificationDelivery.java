package com.ecofood.app.notification.delivery;

import com.ecofood.app.notification.config.NotificationSettings;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.exception.DeliveryFailure;
import com.ecofood.app.notification.service.NotificationRecipients;
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
  private static final String LAST_ERROR = "lastError";
  private static final String LEASE_UNTIL = "leaseUntil";
  private static final String STATUS = "status";
  private static final String PROCESSING = "PROCESSING";
  private static final String NEXT_ATTEMPT_AT = "nextAttemptAt";
  private static final String DELIVERY_UNCERTAIN = "deliveryUncertain";
  private static final String LEASE_TOKEN = "leaseToken";
  private final MongoTemplate mongo;
  private final NotificationEmailSender sender;
  private final NotificationRecipients recipients;
  private final NotificationSettings settings;
  @Scheduled(fixedDelayString = "${ecofood.notification.delivery-poll-ms:5000}", initialDelayString = "${ecofood.notification.initial-delay-ms:20000}")
  public void poll() {
    try {
      // Never automatically resend an expired SMTP attempt with an unknown external outcome.
      mongo.updateMulti(Query.query(Criteria.where(STATUS).is(PROCESSING).and(LEASE_UNTIL).lte(Instant.now())),
          new Update().set(STATUS, "FAILED").set(DELIVERY_UNCERTAIN, true).set(LAST_ERROR, "SMTP_OUTCOME_UNCERTAIN")
            .unset(LEASE_TOKEN).unset(LEASE_UNTIL), Notification.class);
      for (int i = 0; i < 20; i++) {
        Notification work = claim(Instant.now());
        if (work == null) {
          return;
        }
        deliver(work);
      }
    } catch (RuntimeException unavailable) { /* Persisted queue survives outage; next poll resumes. */ }
  }
  public Notification claim(Instant now) {
    return mongo.findAndModify(Query.query(Criteria.where(STATUS).in("PENDING", "RETRY_PENDING")
        .and(NEXT_ATTEMPT_AT).lte(now).and("retryCount").lt(settings.maxAttempts)),
        new Update().set(STATUS, PROCESSING).set(LEASE_TOKEN, UUID.randomUUID().toString())
            .set(LEASE_UNTIL, now.plusSeconds(300)).inc("retryCount", 1).inc("totalAttempts", 1),
        FindAndModifyOptions.options().returnNew(true), Notification.class);
  }
  private Query owned(Notification work) {
    return Query.query(Criteria.where("_id").is(work.getId()).and("tenantId").is(work.getTenantId())
        .and(STATUS).is(PROCESSING).and(LEASE_TOKEN).is(work.getLeaseToken()).and(LEASE_UNTIL).gt(Instant.now()));
  }
  public void deliver(Notification work) {
    // Check fencing immediately before invoking the external provider.
    if (!mongo.exists(owned(work), Notification.class)) return;
    try {
      if (!NotificationSettings.validEmail(work.getSendFrom()) || !NotificationSettings.validEmail(work.getSendTo())) throw new DeliveryFailure("EMAIL_INVALID", false, false);
      if (!recipients.stillAllowed(work)) throw new DeliveryFailure("RECIPIENT_SCOPE_REVOKED", false, false);
      sender.send(work);
    } catch (DeliveryFailure failure) {
      boolean retry = failure.retryable && !failure.uncertain && work.getRetryCount() < settings.maxAttempts;
      mongo.updateFirst(owned(work), new Update().set(STATUS, retry ? "RETRY_PENDING" : "FAILED")
          .set(NEXT_ATTEMPT_AT, Instant.now().plusSeconds(backoff(work.getRetryCount())))
          .set(DELIVERY_UNCERTAIN, failure.uncertain).set(LAST_ERROR, failure.code).unset(LEASE_TOKEN).unset(LEASE_UNTIL), Notification.class);
      return;
    }
    // If this write fails, retain PROCESSING; lease recovery marks the outcome uncertain, never blindly resends.
    mongo.updateFirst(owned(work), new Update().set(STATUS, "SENT").set("sentAt", Instant.now())
        .set(DELIVERY_UNCERTAIN, false).unset(LAST_ERROR).unset(LEASE_TOKEN).unset(LEASE_UNTIL).unset(NEXT_ATTEMPT_AT), Notification.class);
  }
  static long backoff(int attempt) { return Math.min(3600, 60L << Math.min(6, Math.max(0, attempt - 1))); }
}
