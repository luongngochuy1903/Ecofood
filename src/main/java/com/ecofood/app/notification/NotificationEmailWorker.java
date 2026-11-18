package com.ecofood.app.notification;

import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.notification.NotificationContracts.*;
import java.time.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name = "ecofood.notifications.events-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationEmailWorker {
  private final MongoTemplate mongo;
  private final NotificationService notifications;
  private final CustomerIdentityService identities;
  private final EmailDeliveryAdapter adapter;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${ecofood.notifications.poll-ms:5000}", initialDelayString = "${ecofood.notifications.initial-delay-ms:15000}")
  public void poll() {
    if (!adapter.enabled()) return; // PENDING remains honest; no SENT/CAPTURED without provider acceptance.
    for (int i = 0; i < 25; i++) {
      try { if (!deliverOne()) return; }
      catch (RuntimeException ex) { log.warn("Notification email polling deferred ({})", ex.getClass().getSimpleName()); return; }
    }
  }

  boolean deliverOne() {
    if (!adapter.enabled()) return false;
    Instant now = clock.instant(); String lease = UUID.randomUUID().toString();
    var query = new Query(new Criteria().andOperator(Criteria.where("deleted").ne(true).and("deliveryChannel").is(Channel.EMAIL).and("deliveryStatus").is(DeliveryStatus.PENDING),
        new Criteria().orOperator(Criteria.where("nextDeliveryAttemptAt").is(null), Criteria.where("nextDeliveryAttemptAt").lte(now)),
        new Criteria().orOperator(Criteria.where("deliveryLeaseUntil").is(null), Criteria.where("deliveryLeaseUntil").lte(now))));
    var row = mongo.findAndModify(query, new Update().set("deliveryLeaseId", lease).set("deliveryLeaseUntil", now.plusSeconds(120))
        .inc("deliveryAttempts", 1), FindAndModifyOptions.options().returnNew(true), Notification.class);
    if (row == null) return false;
    var owned = new Query(Criteria.where("_id").is(row.getId()).and("deliveryLeaseId").is(lease).and("deliveryStatus").is(DeliveryStatus.PENDING));
    try {
      var decision = notifications.prepareDelivery(row.getIdAsString());
      if (!decision.allowed()) { finish(owned, DeliveryStatus.SUPPRESSED, decision.reason(), now); return true; }
      // requireAccount verifies current enabled + verified Keycloak email, not an address from the event.
      var customer = identities.requireAccount(row.getRecipientCustomerId());
      // Recheck preferences immediately before invoking the adapter, including consent withdrawal.
      decision = notifications.prepareDelivery(row.getIdAsString());
      if (!decision.allowed()) { finish(owned, DeliveryStatus.SUPPRESSED, decision.reason(), now); return true; }
      String text = row.getType() == Type.ACCOUNT ? "Tài khoản EcoFood của bạn có cập nhật. Vui lòng đăng nhập Customer Portal để kiểm tra."
          : "Bạn có thông báo mới trên EcoFood. Vui lòng đăng nhập Customer Portal để kiểm tra.";
      var result = adapter.deliver(new EmailDeliveryAdapter.Message(row.getIdAsString(), row.getRecipientCustomerId(), customer.getEmail(), "Thông báo EcoFood", text));
      if (result == null || result.provider() == null || result.messageId() == null) throw new IllegalStateException("Invalid delivery acknowledgement");
      var update = cleared(now).set("deliveryStatus", result.captured() ? DeliveryStatus.CAPTURED : DeliveryStatus.SENT)
          .set("deliveryProvider", result.provider()).set("providerMessageId", result.messageId()).set("deliveryCompletedAt", now)
          .unset("lastDeliveryError").unset("nextDeliveryAttemptAt");
      if (!result.captured()) update.set("sentAt", now);
      mongo.updateFirst(owned, update, Notification.class);
    } catch (RuntimeException ex) {
      boolean permanent = ex instanceof IllegalArgumentException || ex instanceof com.ecofood.app.config.BadRequestException
          || ex instanceof ResponseStatusException status && (status.getStatusCode().value() == 401 || status.getStatusCode().value() == 403 || status.getStatusCode().value() == 404);
      if (permanent) finish(owned, DeliveryStatus.SUPPRESSED, "RECIPIENT_OR_REFERENCE_UNAVAILABLE", now);
      else {
        boolean exhausted = row.getDeliveryAttempts() >= 10;
        mongo.updateFirst(owned, cleared(now).set("deliveryStatus", exhausted ? DeliveryStatus.FAILED : DeliveryStatus.PENDING)
            .set("lastDeliveryError", "EMAIL_DELIVERY_FAILED").set("nextDeliveryAttemptAt", now.plusSeconds(NotificationOutboxWorker.backoff(row.getDeliveryAttempts()))), Notification.class);
      }
      log.warn("Notification email delivery deferred or suppressed; no provider response or address logged");
    }
    return true;
  }
  private void finish(Query query, DeliveryStatus status, String reason, Instant now) {
    mongo.updateFirst(query, cleared(now).unset("nextDeliveryAttemptAt").set("deliveryStatus", status).set("lastDeliveryError", reason).set("suppressionReason", reason), Notification.class);
  }
  private Update cleared(Instant now) {
    return new Update().unset("deliveryLeaseId").unset("deliveryLeaseUntil")
        .set("updatedAt", now).set("updatedBy", "NOTIFICATION_DELIVERY");
  }
}
