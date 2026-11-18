package com.ecofood.app.customer.claim;

import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class InvoiceClaimMongoConfig {
  @Bean
  public ApplicationRunner invoiceClaimIndexes(MongoTemplate mongo) {
    return args -> {
      mongo.indexOps(CustomerInvoiceLink.class).createIndex(new Index().on("invoice_identifier", Sort.Direction.ASC)
          .unique().named("ux_customer_invoice_owner"));
      mongo.indexOps(CustomerInvoiceLink.class).createIndex(new Index().on("customer_identifier", Sort.Direction.ASC)
          .on("customer_tenant_identifier", Sort.Direction.ASC).on("claimed_at", Sort.Direction.DESC)
          .named("ix_customer_claims_date"));
      // TTL is cleanup only. Services enforce expiry before every claim independently of Mongo's TTL monitor.
      mongo.indexOps(InvoiceClaimProof.class).createIndex(new Index().on("expiresAt", Sort.Direction.ASC)
          .expire(Duration.ZERO).named("ttl_invoice_claim_proof"));
    };
  }
}
