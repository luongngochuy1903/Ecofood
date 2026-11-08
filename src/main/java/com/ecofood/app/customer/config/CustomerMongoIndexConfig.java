package com.ecofood.app.customer.config;

import com.ecofood.app.customer.entity.CustomerProfileEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/** Make the uniqueness invariant effective even when auto-index-creation is disabled. */
@Configuration
@RequiredArgsConstructor
public class CustomerMongoIndexConfig {
  private final MongoTemplate mongoTemplate;

  @Bean
  public ApplicationRunner customerProfileUniqueIndex() {
    return args -> mongoTemplate.indexOps(CustomerProfileEntity.class).ensureIndex(
        new Index().on("tenant_identifier", Sort.Direction.ASC)
            .on("user_identifier", Sort.Direction.ASC)
            .unique().named("ux_customer_profile_tenant_user"));
  }
}
