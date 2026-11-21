package com.ecofood.app.transaction.config;

import com.ecofood.app.transaction.entity.Invoice;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class TransactionMongoConfig {
  @Bean
  ApplicationRunner invoiceIndexes(MongoTemplate mongo) {
    return args -> {
      mongo
          .indexOps(Invoice.class)
          .ensureIndex(
              new Index()
                  .on("tenantId", Sort.Direction.ASC)
                  .on("posId", Sort.Direction.ASC)
                  .on("invoiceNumber", Sort.Direction.ASC)
                  .unique()
                  .named("ux_invoice_tenant_pos_number"));
      mongo
          .indexOps(Invoice.class)
          .ensureIndex(
              new Index()
                  .on("tenantId", Sort.Direction.ASC)
                  .on("receivedAt", Sort.Direction.DESC)
                  .named("ix_invoice_tenant_received"));
    };
  }
}
