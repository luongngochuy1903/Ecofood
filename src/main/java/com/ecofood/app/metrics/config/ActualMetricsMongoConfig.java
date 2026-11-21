package com.ecofood.app.metrics.config;

import com.ecofood.app.metrics.entity.ActualMetrics;
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
      mongo.indexOps(ActualMetrics.class).ensureIndex(new Index()
          .on("propositionId", Sort.Direction.ASC).on("tenantId", Sort.Direction.ASC)
          .on("year", Sort.Direction.ASC).on("month", Sort.Direction.ASC).on("currency", Sort.Direction.ASC)
          .unique().named("ux_actual_metrics_monthly_tenant_currency"));
      mongo.indexOps(Submission.class).ensureIndex(new Index().on("event", Sort.Direction.ASC)
          .on("sourceType", Sort.Direction.ASC).on("actualMetrics.state", Sort.Direction.ASC)
          .on("actualMetrics.nextRetryAt", Sort.Direction.ASC).named("ix_submission_metrics_retry"));
      mongo.indexOps(Invoice.class).ensureIndex(new Index().on("propositionId", Sort.Direction.ASC)
          .on("tenantId", Sort.Direction.ASC).on("currency", Sort.Direction.ASC)
          .on("receivedAt", Sort.Direction.ASC).named("ix_invoice_metrics_reconciliation"));
    };
  }
}
