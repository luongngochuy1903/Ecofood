package com.ecofood.app.orchestrator.config;

import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
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
                  .named("ix_orchestrator_tenant_proposition"));
      // A Plan belongs to at most one Sale Orchestrator per Tenant — a
      // unique index on an array field (planIds) enforces per-element
      // uniqueness across documents, exactly this invariant. Partial on
      // planIds existing so leftover documents from the pre-redesign,
      // one-document-per-Plan schema (no planIds field at all) don't
      // collide with each other under a shared null key and block the
      // index build.
      mongo
          .indexOps(PlanSaleOrchestrator.class)
          .ensureIndex(
              new Index()
                  .on("tenantId", Sort.Direction.ASC)
                  .on("planIds", Sort.Direction.ASC)
                  .unique()
                  .partial(PartialIndexFilter.of(Criteria.where("planIds").exists(true)))
                  .named("ux_orchestrator_tenant_plan"));
    };
  }
}
