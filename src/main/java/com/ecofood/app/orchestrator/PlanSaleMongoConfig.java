package com.ecofood.app.orchestrator;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.Criteria;

@Configuration
public class PlanSaleMongoConfig {
  @Bean
  ApplicationRunner planSaleIndexes(MongoTemplate mongo) {
    return args -> {
      mongo
          .indexOps(PlanSaleOrchestrator.class)
          .ensureIndex(
              new Index()
                  .on("tenantId", Sort.Direction.ASC)
                  .on("propositionId", Sort.Direction.ASC)
                  .on("planId", Sort.Direction.ASC)
                  .unique()
                  .partial(
                      PartialIndexFilter.of(
                          Criteria.where("tenantId")
                              .exists(true)
                              .and("propositionId")
                              .exists(true)
                              .and("planId")
                              .exists(true)))
                  .named("ux_orchestrator_tenant_proposition_plan"));
      mongo
          .indexOps(PlanSaleOrchestrator.class)
          .ensureIndex(
              new Index()
                  .on("tenantId", Sort.Direction.ASC)
                  .on("activeVersion", Sort.Direction.ASC)
                  .named("ix_orchestrator_tenant_active"));
    };
  }
}
