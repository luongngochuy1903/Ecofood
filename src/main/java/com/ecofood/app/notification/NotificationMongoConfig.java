package com.ecofood.app.notification;

import com.ecofood.app.submission.entity.Submission;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class NotificationMongoConfig {
  @Bean ApplicationRunner notificationIndexes(MongoTemplate mongo) {
    return args -> {
      mongo.indexOps(Notification.class).createIndex(new Index().on("submissionId", Sort.Direction.ASC)
          .on("ruleId", Sort.Direction.ASC).on("sendTo", Sort.Direction.ASC).on("channel", Sort.Direction.ASC).unique().named("ux_notification_delivery"));
      mongo.indexOps(Notification.class).createIndex(new Index().on("status", Sort.Direction.ASC).on("nextAttemptAt", Sort.Direction.ASC).on("leaseUntil", Sort.Direction.ASC).named("ix_notification_delivery_due"));
      mongo.indexOps(Notification.class).createIndex(new Index().on("tenantId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC).named("ix_notification_tenant_history"));
      mongo.indexOps(NotificationRule.class).createIndex(new Index().on("tenantId", Sort.Direction.ASC).on("eventType", Sort.Direction.ASC).on("enabled", Sort.Direction.ASC).named("ix_notification_rule_event"));
      mongo.indexOps(Submission.class).createIndex(new Index().on("event", Sort.Direction.ASC).on("notification.state", Sort.Direction.ASC)
          .on("notification.nextAttemptAt", Sort.Direction.ASC).on("notification.leaseUntil", Sort.Direction.ASC).named("ix_submission_notification_due"));
    };
  }
}
