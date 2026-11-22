package com.ecofood.app.transaction.config;

import com.ecofood.app.transaction.entity.Invoice;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class TransactionMongoConfig {
  private static final String TENANT_ID = "tenantId";
  @Bean
  ApplicationRunner invoiceIndexes(MongoTemplate mongo) {
    return args -> {
      mongo
          .indexOps(Invoice.class)
          .ensureIndex(
              new Index()
                  .on(TENANT_ID, Sort.Direction.ASC)
                  .on("posId", Sort.Direction.ASC)
                  .on("invoiceNumber", Sort.Direction.ASC)
                  .unique()
                  .named("ux_invoice_tenant_pos_number"));
      mongo
          .indexOps(Invoice.class)
          .ensureIndex(
              new Index()
                  .on(TENANT_ID, Sort.Direction.ASC)
                  .on("receivedAt", Sort.Direction.DESC)
                  .named("ix_invoice_tenant_received"));
      mongo.indexOps(Invoice.class).ensureIndex(new Index()
          .on("submissionPending", Sort.Direction.ASC)
          .on("submissionRetryAt", Sort.Direction.ASC)
          .named("ix_invoice_submission_pending"));
      mongo.indexOps(Invoice.class).ensureIndex(new Index().on(TENANT_ID,Sort.Direction.ASC)
          .on("planLines.snapshot.propositionId",Sort.Direction.ASC).on("currency",Sort.Direction.ASC)
          .on("receivedAt",Sort.Direction.ASC).named("ix_invoice_plan_lines_metrics"));
    };
  }
}
