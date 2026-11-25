package com.ecofood.app.orchestrator;

import static com.ecofood.app.orchestrator.SaleFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.repository.query.parser.PartTree;

class PlanSaleMongoMappingTest {
  @Test
  void roundTripsIdentityRevisionAndPlanListWithoutDatabase() throws Exception {
    var conversions =
        new com.ecofood.app.proposition.service.PropositionMongoConfiguration()
            .propositionMongoConversions();
    var context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
    var row = new PlanSaleOrchestrator();
    row.setId("orch");
    row.setTenantId(TENANT);
    row.setPropositionId(PROP);
    row.setRevision(2L);
    row.setName("Sale A");
    row.setDescription("Covers factory plans");
    row.setPlanIds(List.of(PLAN, OTHER_PLAN));
    row.setCreatedAt(Instant.now());
    row.setUpdatedAt(Instant.now());
    row.setCreatedBy("employee");
    row.setUpdatedBy("employee");
    var document = new Document();
    converter.write(row, document);
    var copy = converter.read(PlanSaleOrchestrator.class, document);
    assertEquals(PROP, copy.getPropositionId());
    assertEquals(2L, copy.getRevision());
    assertEquals(List.of(PLAN, OTHER_PLAN), copy.getPlanIds());
    assertEquals("Sale A", copy.getName());
  }

  @Test
  void repositoryQueriesUseTenantScopedProperties() {
    var byTenantAndProposition =
        new PartTree("findByTenantIdAndPropositionId", PlanSaleOrchestrator.class);
    assertEquals(
        List.of("tenantId", "propositionId"),
        byTenantAndProposition.getParts().stream().map(p -> p.getProperty().toDotPath()).toList());
    var byIdTenantAndProposition =
        new PartTree("findByIdAndTenantIdAndPropositionId", PlanSaleOrchestrator.class);
    assertEquals(
        List.of("id", "tenantId", "propositionId"),
        byIdTenantAndProposition.getParts().stream()
            .map(p -> p.getProperty().toDotPath())
            .toList());
    var byTenantAndPlan =
        new PartTree("findByTenantIdAndPlanIdsContaining", PlanSaleOrchestrator.class);
    assertEquals(
        List.of("tenantId", "planIds"),
        byTenantAndPlan.getParts().stream().map(p -> p.getProperty().toDotPath()).toList());
  }
}
