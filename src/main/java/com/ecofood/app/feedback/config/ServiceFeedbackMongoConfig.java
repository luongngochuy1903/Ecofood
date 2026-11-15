package com.ecofood.app.feedback.config;

import com.ecofood.app.feedback.entity.ServiceFeedback;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class ServiceFeedbackMongoConfig {
  @Bean
  ApplicationRunner serviceFeedbackUniqueIndex(MongoTemplate mongo) {
    return args -> mongo.indexOps(ServiceFeedback.class).ensureIndex(new Index()
        .on("tenant_identifier", Sort.Direction.ASC).on("invoice_identifier", Sort.Direction.ASC)
        .unique().named("ux_service_feedback_tenant_invoice"));
  }
}
