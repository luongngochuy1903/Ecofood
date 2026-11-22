package com.ecofood.app.product.config;

import com.ecofood.app.proposition.entity.PropositionEntity;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@Configuration
public class ProductVersionReviewMongoConfig {
  @Bean
  public ApplicationRunner repairProductVersionReviewActor(MongoTemplate mongo) {
    return args -> {
      // Update directly: reading these legacy documents as entities fails ObjectId conversion.
      // The system actor remains recorded in changes.actorId. The exact filter makes retries safe.
      mongo.updateMulti(
          Query.query(Criteria.where("updatedBy").is("PRODUCT_VERSION_REVIEW")),
          new Update().unset("updatedBy").inc("revision", 1),
          PropositionEntity.class);
    };
  }
}
