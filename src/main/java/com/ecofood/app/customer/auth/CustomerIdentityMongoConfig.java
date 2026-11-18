package com.ecofood.app.customer.auth;

import com.ecofood.app.userACL.entity.UserEntity;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;

@Configuration
public class CustomerIdentityMongoConfig {
  @Bean
  public ApplicationRunner customerIdentityIndexes(MongoTemplate mongo) {
    return args -> {
      mongo.indexOps(UserEntity.class).createIndex(new Index()
          .on("customer_identity_issuer", Sort.Direction.ASC).on("external_login_id", Sort.Direction.ASC)
          .unique().partial(PartialIndexFilter.of(Criteria.where("customer_identity_issuer").type(2)))
          .named("ux_customer_issuer_subject"));
      mongo.indexOps(UserEntity.class).createIndex(new Index().on("customer_email_normalized", Sort.Direction.ASC)
          .unique().partial(PartialIndexFilter.of(Criteria.where("customer_email_normalized").type(2)))
          .named("ux_customer_email"));
    };
  }
}
