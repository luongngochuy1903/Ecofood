package com.ecofood.app.receipt.config;

import com.ecofood.app.receipt.entity.ReceiptAccess;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class ReceiptConfig {
  @Bean
  Clock receiptClock() { return Clock.systemUTC(); }

  @Bean
  ApplicationRunner receiptTokenIndex(MongoTemplate mongo) {
    return args -> mongo.indexOps(ReceiptAccess.class).ensureIndex(
        new Index().on("tokenHash", Sort.Direction.ASC).unique().named("ux_receipt_token_hash"));
  }
}
