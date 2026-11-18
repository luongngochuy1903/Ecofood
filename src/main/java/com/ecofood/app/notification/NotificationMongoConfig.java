package com.ecofood.app.notification;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class NotificationMongoConfig {
  @Bean public ApplicationRunner notificationIndexes(MongoTemplate mongo) {
    return args -> {
      mongo.indexOps(Notification.class).createIndex(new Index().on("customer_tenant_identifier", Sort.Direction.ASC)
          .on("recipient_customer_identifier", Sort.Direction.ASC).on("source_tenant_identifier", Sort.Direction.ASC)
          .on("event_id", Sort.Direction.ASC).on("delivery_channel", Sort.Direction.ASC).unique().named("ux_notification_event_recipient_channel"));
      mongo.indexOps(Notification.class).createIndex(new Index().on("customer_tenant_identifier", Sort.Direction.ASC)
          .on("recipient_customer_identifier", Sort.Direction.ASC).on("delivery_channel", Sort.Direction.ASC)
          .on("delivery_status", Sort.Direction.ASC).on("is_read", Sort.Direction.ASC)
          .on("created_time_stamp", Sort.Direction.DESC).on("_id", Sort.Direction.DESC).named("ix_notification_customer_inbox"));
      mongo.indexOps(NotificationPreference.class).createIndex(new Index().on("customer_tenant_identifier", Sort.Direction.ASC)
          .on("customer_identifier", Sort.Direction.ASC).unique().named("ux_notification_preferences_customer"));
    };
  }
}
