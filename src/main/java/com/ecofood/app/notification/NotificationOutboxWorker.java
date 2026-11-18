package com.ecofood.app.notification;

import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Durable embedded outbox; no Mongo multi-document transaction/replica set is required. */
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name = "ecofood.notifications.events-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationOutboxWorker {
  private static final List<String> SOURCES = List.of("customer_invoice_link", "app_user", "customer_profile");
  private final MongoTemplate mongo;
  private final NotificationEventHandler handler;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${ecofood.notifications.poll-ms:5000}", initialDelayString = "${ecofood.notifications.initial-delay-ms:15000}")
  public void poll() {
    for (String source : SOURCES) {
      try { drain(source); }
      catch (RuntimeException ex) { log.warn("Notification outbox polling deferred for {} ({})", source, ex.getClass().getSimpleName()); }
    }
  }

  void drain(String source) {
    if (!SOURCES.contains(source)) throw new IllegalArgumentException("Invalid outbox source");
    Instant now = clock.instant();
    var query = new Query(Criteria.where("deleted").ne(true).and("notificationOutbox").elemMatch(due(now))).limit(25);
    for (Document owner : mongo.find(query, Document.class, source)) {
      var entries = owner.getList("notificationOutbox", Document.class, List.of());
      for (Document entry : entries) {
        String id = entry.getString("eventId"), lease = UUID.randomUUID().toString();
        if (id == null) continue;
        var claim = new Query(Criteria.where("_id").is(owner.getObjectId("_id")).and("deleted").ne(true)
            .and("notificationOutbox").elemMatch(new Criteria().andOperator(Criteria.where("eventId").is(id), due(now))));
        var locked = mongo.findAndModify(claim, new Update().set("notificationOutbox.$.leaseId", lease)
            .set("notificationOutbox.$.leaseUntil", now.plusSeconds(120)), FindAndModifyOptions.options().returnNew(true), Document.class, source);
        if (locked == null) continue;
        Document payload = locked.getList("notificationOutbox", Document.class).stream().filter(e -> id.equals(e.getString("eventId"))).findFirst().orElseThrow();
        var event = mongo.getConverter().read(NotificationOutboxEvent.class, payload);
        var owned = owned(owner.getObjectId("_id"), id, lease);
        try {
          validateSource(source, locked, event);
          handler.handle(event);
          // Ack only after both durable channel records exist. A crash before ack safely replays.
          mongo.updateFirst(owned, new Update().pull("notificationOutbox", new Document("eventId", id).append("leaseId", lease)), source);
        } catch (RuntimeException ex) {
          String reason = ex instanceof SecurityException ? "SOURCE_CONTEXT_INVALID" : "EVENT_PROCESSING_FAILED";
          mongo.updateFirst(owned, new Update().inc("notificationOutbox.$.attempts", 1)
              .set("notificationOutbox.$.lastError", reason).set("notificationOutbox.$.nextAttemptAt", now.plusSeconds(backoff(event.attempts())))
              .unset("notificationOutbox.$.leaseId").unset("notificationOutbox.$.leaseUntil"), source);
          log.warn("Notification event deferred ({})", reason);
        }
      }
    }
  }

  private Criteria due(Instant now) {
    return new Criteria().andOperator(Criteria.where("nextAttemptAt").lte(now),
        new Criteria().orOperator(Criteria.where("leaseUntil").is(null), Criteria.where("leaseUntil").lte(now)));
  }
  private Query owned(ObjectId owner, String event, String lease) {
    return new Query(Criteria.where("_id").is(owner).and("notificationOutbox")
        .elemMatch(Criteria.where("eventId").is(event).and("leaseId").is(lease)));
  }
  static long backoff(int attempts) { return Math.min(3600, 5L << Math.min(10, Math.max(0, attempts))); }

  static void validateSource(String source, Document owner, NotificationOutboxEvent e) {
    boolean valid = switch (source) {
      case "customer_invoice_link" -> e.kind() == NotificationOutboxEvent.Kind.INVOICE_CLAIMED
          && "CLAIMED".equals(owner.getString("claim_status"))
          && Objects.equals(owner.getString("customer_identifier"), e.customerId())
          && Objects.equals(owner.getString("customer_tenant_identifier"), e.portalId())
          && Objects.equals(owner.getString("tenant_identifier"), e.sourceTenantId())
          && Objects.equals(owner.getString("distribution_partner_identifier"), e.distributionPartnerId())
          && Objects.equals(owner.getString("invoice_identifier"), e.referenceId())
          && ("invoice-claimed:" + owner.getObjectId("_id")).equals(e.eventId());
      case "app_user" -> (e.kind() == NotificationOutboxEvent.Kind.CUSTOMER_REGISTERED || e.kind() == NotificationOutboxEvent.Kind.VERIFIED_EMAIL_CHANGED)
          && owner.getObjectId("_id").toHexString().equals(e.customerId());
      case "customer_profile" -> e.kind() == NotificationOutboxEvent.Kind.PROFILE_UPDATED
          && Objects.equals(owner.get("user_identifier"), new ObjectId(e.customerId()))
          && Objects.equals(owner.get("tenant_identifier"), new ObjectId(e.portalId()));
      default -> false;
    };
    if (!valid || (e.kind() != NotificationOutboxEvent.Kind.INVOICE_CLAIMED
        && (!Objects.equals(e.portalId(), e.sourceTenantId()) || !Objects.equals(e.customerId(), e.referenceId()) || e.distributionPartnerId() != null)))
      throw new SecurityException("Invalid notification event source");
  }
}
