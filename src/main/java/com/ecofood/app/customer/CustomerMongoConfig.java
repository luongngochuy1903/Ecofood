package com.ecofood.app.customer;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.Criteria;

@Configuration
public class CustomerMongoConfig {
  @Bean ApplicationRunner customerIndexes(MongoTemplate mongo) {
    return args -> {
      mongo.indexOps(CustomerInvoice.class).createIndex(new Index().on("customerReceipt.tokenHash", Sort.Direction.ASC).unique()
          .partial(PartialIndexFilter.of(Criteria.where("customerReceipt.tokenHash").type(2))).named("ux_invoice_customer_receipt_hash"));
      mongo.indexOps(CustomerInvoice.class).createIndex(new Index().on("tenantId", Sort.Direction.ASC).on("invoiceHolder.userId", Sort.Direction.ASC)
          .on("receivedAt", Sort.Direction.DESC).named("ix_invoice_customer_history"));
      mongo.indexOps(CustomerInvoice.class).createIndex(new Index().on("customerClaimHandoff.pending", Sort.Direction.ASC)
          .on("customerClaimHandoff.nextRetryAt", Sort.Direction.ASC).named("ix_invoice_customer_claim_handoff"));
    };
  }
}
