package com.ecofood.app.metrics.config;

import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class ActualMetricsMongoConfig {
  @Bean
  public ApplicationRunner actualMetricsIndexes(MongoTemplate mongo) {
    return args -> {
      mongo.indexOps(Submission.class).ensureIndex(new Index().on("sourceType",Sort.Direction.ASC)
          .on("sellerConfirmation.pending",Sort.Direction.ASC).on("sellerConfirmation.nextRetryAt",Sort.Direction.ASC)
          .named("ix_submission_seller_confirmation_retry"));
      mongo.indexOps(Invoice.class).ensureIndex(new Index().on("propositionId", Sort.Direction.ASC)
          .on("tenantId", Sort.Direction.ASC).on("currency", Sort.Direction.ASC)
          .on("receivedAt", Sort.Direction.ASC).named("ix_invoice_metrics_reconciliation"));
    };
  }
}
