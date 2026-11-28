package com.ecofood.app.transaction.config;

import com.ecofood.app.transaction.entity.Invoice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@Slf4j
@Configuration
public class TransactionMongoConfig {
  private static final String TENANT_ID = "tenantId";
  /** Unique (tenantId, posId, invoiceNumber); invoiceNumber no longer exists, so every new row would collide on null. */
  private static final String LEGACY_NUMBER_INDEX = "ux_invoice_tenant_pos_number";

  @Bean
  ApplicationRunner invoiceIndexes(MongoTemplate mongo) {
    return args -> {
      dropLegacyFields(mongo);
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

  /** Idempotent: removes the retired type/paymentStatus/invoiceNumber fields and their unique index. */
  static void dropLegacyFields(MongoTemplate mongo) {
    var ops = mongo.indexOps(Invoice.class);
    if (ops.getIndexInfo().stream().anyMatch(index -> LEGACY_NUMBER_INDEX.equals(index.getName()))) {
      ops.dropIndex(LEGACY_NUMBER_INDEX);
    }
    var legacy = Query.query(new Criteria().orOperator(Criteria.where("type").exists(true),
        Criteria.where("paymentStatus").exists(true), Criteria.where("invoiceNumber").exists(true)));
    var result = mongo.updateMulti(legacy,
        new Update().unset("type").unset("paymentStatus").unset("invoiceNumber"), Invoice.class);
    if (result.getModifiedCount() > 0) {
      log.info("Removed retired type/paymentStatus/invoiceNumber from {} invoices", result.getModifiedCount());
    }
  }
}
