package com.ecofood.app.notification;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration @EnableScheduling
public class NotificationDeliveryConfig {
  @Bean public ApplicationRunner notificationDeliveryIndexes(MongoTemplate mongo) {
    return args -> {
      for (String source : java.util.List.of("customer_invoice_link", "app_user", "customer_profile"))
        mongo.indexOps(source).createIndex(new Index().on("notificationOutbox.nextAttemptAt", Sort.Direction.ASC).named("ix_notification_outbox_due"));
      mongo.indexOps(Notification.class).createIndex(new Index().on("delivery_channel", Sort.Direction.ASC)
          .on("delivery_status", Sort.Direction.ASC).on("nextDeliveryAttemptAt", Sort.Direction.ASC).named("ix_notification_email_due"));
    };
  }
}
