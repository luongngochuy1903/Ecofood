package com.ecofood.app.notification;

import com.ecofood.app.submission.entity.Submission;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationProcessor {
  private final MongoTemplate mongo;
  private final List<NotificationEventSource> sources;
  private final NotificationRecipients recipients;
  private final NotificationTemplate template;
  private final NotificationSettings settings;
  @Scheduled(fixedDelayString = "${ecofood.notification.poll-ms:5000}", initialDelayString = "${ecofood.notification.initial-delay-ms:20000}")
  public void poll() {
    for (int i = 0; i < 20; i++) {
      SubmissionNotificationState claimed;
      try { claimed = claim(Instant.now()); } catch (RuntimeException unavailable) { return; }
      if (claimed == null) return;
      process(claimed);
    }
  }
  SubmissionNotificationState claim(Instant now) {
    Criteria due = new Criteria().orOperator(Criteria.where("notification.state").exists(false),
        new Criteria().andOperator(Criteria.where("notification.state").is("RETRY"), Criteria.where("notification.nextAttemptAt").lte(now)),
        new Criteria().andOperator(Criteria.where("notification.state").is("PROCESSING"), Criteria.where("notification.leaseUntil").lte(now)));
    return mongo.findAndModify(Query.query(new Criteria().andOperator(Criteria.where("event").in(sources.stream().map(NotificationEventSource::event).toList()), due)),
        new Update().set("notification.state", "PROCESSING").set("notification.leaseToken", UUID.randomUUID().toString())
            .set("notification.leaseUntil", now.plusSeconds(120)).inc("notification.attempts", 1),
        FindAndModifyOptions.options().returnNew(true), SubmissionNotificationState.class);
  }
  private Query owned(SubmissionNotificationState work) {
    return Query.query(Criteria.where("_id").is(work.getId()).and("tenantId").is(work.getTenantId())
        .and("notification.state").is("PROCESSING").and("notification.leaseToken").is(work.getNotification().getLeaseToken())
        .and("notification.leaseUntil").gt(Instant.now()));
  }
  void process(SubmissionNotificationState work) {
    try {
      List<Notification> plan = work.getNotification().getPlan(); String result = work.getNotification().getResult();
      if (plan == null) {
        Submission event = mongo.findOne(Query.query(Criteria.where("_id").is(work.getId()).and("tenantId").is(work.getTenantId())), Submission.class);
        if (event == null) throw new IllegalStateException("EVENT_NOT_FOUND");
        NotificationEventSource.Context context = sources.stream().filter(s -> s.event().equals(event.event())).findFirst().orElseThrow().resolve(event);
        plan = new ArrayList<>(); result = "WORKFLOW_DISABLED";
        if (context.workflowEnabled()) {
          var rules = mongo.find(Query.query(Criteria.where("tenantId").is(event.tenantId()).and("eventType").is(event.event()).and("enabled").is(true).and("channel").is("EMAIL")).limit(101), NotificationRule.class);
          if (rules.size() > 100) throw new IllegalStateException("RULE_LIMIT_EXCEEDED");
          result = rules.isEmpty() ? "NO_RULE" : "NO_VALID_RECIPIENT";
          for (NotificationRule rule : rules) {
            var targets = Submission.INVOICE_PAID.equals(event.event()) ? recipients.resolve(rule, context.invoice()) : recipients.resolve(rule, context.invoice(), event);
            if (targets.isEmpty()) continue;
            String subject = template.render(rule.getSubject(), context.values(), true), body = template.render(rule.getContent(), context.values(), false);
            for (var recipient : targets) {
              Notification notification = new Notification();
              notification.setId(deliveryId(event.id(), rule.getId(), recipient.email()));
              notification.setSubmissionId(event.id()); notification.setTenantId(event.tenantId()); notification.setRuleId(rule.getId());
              notification.setType(event.event()); notification.setRuleRevision(rule.getRevision()); notification.setSubject(subject); notification.setContent(body);
              notification.setSendFrom(settings.from); notification.setSendTo(recipient.email()); notification.setRecipientUserId(recipient.userId());
              notification.setCreatedAt(Instant.now()); notification.setNextAttemptAt(Instant.now()); plan.add(notification);
              if (plan.size() > 200) throw new IllegalStateException("DELIVERY_LIMIT_EXCEEDED");
            }
          }
          if (!plan.isEmpty()) result = "GENERATED";
        }
        if (!plan.isEmpty() && !NotificationSettings.validEmail(settings.from)) throw new IllegalStateException("SMTP_FROM_NOT_CONFIGURED");
        // Freeze all decisions before inserting any deliveries. A failed/expired claim cannot replace this plan.
        Query freeze = owned(work); freeze.addCriteria(Criteria.where("notification.plan").exists(false));
        if (mongo.updateFirst(freeze, new Update().set("notification.plan", plan).set("notification.result", result), SubmissionNotificationState.class).getModifiedCount() != 1) return;
      }
      for (Notification notification : plan) {
        try { mongo.insert(notification); }
        catch (DuplicateKeyException duplicate) {
          Notification existing = mongo.findById(notification.getId(), Notification.class);
          if (existing == null || !Objects.equals(existing.getTenantId(), notification.getTenantId())
              || !Objects.equals(existing.getSubmissionId(), notification.getSubmissionId()) || !Objects.equals(existing.getRuleId(), notification.getRuleId())
              || !Objects.equals(existing.getSendTo(), notification.getSendTo())) throw new IllegalStateException("DELIVERY_ID_CONFLICT");
        }
      }
      mongo.updateFirst(owned(work), new Update().set("notification.state", "GENERATED".equals(result) ? "DONE" : "SKIPPED")
          .set("notification.completedAt", Instant.now()).unset("notification.leaseToken").unset("notification.leaseUntil").unset("notification.lastError"), SubmissionNotificationState.class);
    } catch (RuntimeException failure) {
      try {
        boolean exhausted = work.getNotification().getAttempts() >= 10;
        Set<String> safeCodes = Set.of("INVALID_EVENT_SOURCE", "INVALID_INVOICE_SCOPE", "EVENT_NOT_FOUND", "TEMPLATE_DATA_MISSING", "RULE_LIMIT_EXCEEDED", "RECIPIENT_LIMIT_EXCEEDED", "DELIVERY_LIMIT_EXCEEDED", "DELIVERY_ID_CONFLICT", "SMTP_FROM_NOT_CONFIGURED");
        String error = safeCodes.contains(String.valueOf(failure.getMessage())) ? failure.getMessage() : "GENERATION_FAILED";
        mongo.updateFirst(owned(work), new Update().set("notification.state", exhausted ? "BLOCKED" : "RETRY")
            .set("notification.nextAttemptAt", Instant.now().plusSeconds(60)).set("notification.lastError", error)
            .unset("notification.leaseToken").unset("notification.leaseUntil"), SubmissionNotificationState.class);
      } catch (RuntimeException unavailable) { /* Expired lease is recoverable by another poll. */ }
    }
  }
  static String deliveryId(String submission, String rule, String recipient) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((submission + "\n" + rule + "\n" + recipient + "\nEMAIL").getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
}
