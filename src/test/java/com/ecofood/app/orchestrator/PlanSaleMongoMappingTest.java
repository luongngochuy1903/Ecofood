package com.ecofood.app.orchestrator;

import static com.ecofood.app.orchestrator.SaleFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.core.dto.PlanSaleOrchestratorDtos;
import com.ecofood.core.enums.PlanSaleOrchestratorStatusEnum;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.repository.query.parser.PartTree;

class PlanSaleMongoMappingTest {
  @Test
  void roundTripsPlanIdentityVersionsAndDeclarativeSchemaWithoutDatabase() throws Exception {
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
    row.setPlanId(PLAN);
    row.setRevision(2L);
    row.setActiveVersion(1);
    row.getVersions()
        .add(
            new PlanSaleOrchestratorDtos.ConfigVersion(
                1,
                PlanSaleOrchestratorStatusEnum.ACTIVE,
                config(),
                Instant.now(),
                "employee",
                Instant.now(),
                "employee",
                Instant.now(),
                "employee"));
    var document = new Document();
    converter.write(row, document);
    document
        .getList("versions", Document.class)
        .get(0)
        .get("configuration", Document.class)
        .put(
            "posBindings",
            java.util.List.of(
                new Document("posId", "legacy-pos")
                    .append("authenticatedSubject", "legacy-subject")));
    var copy = converter.read(PlanSaleOrchestrator.class, document);
    assertEquals(PLAN, copy.getPlanId());
    assertEquals(2L, copy.getRevision());
    assertEquals(1, copy.getActiveVersion());
    assertEquals(
        "items", copy.getVersions().get(0).configuration().payloadSchema().fields().get(4).name());
    assertEquals(100, copy.getVersions().get(0).configuration().maxItems());
    var saved = new Document();
    converter.write(copy, saved);
    assertFalse(
        saved
            .getList("versions", Document.class)
            .get(0)
            .get("configuration", Document.class)
            .containsKey("posBindings"));
  }

  @Test
  void repositoryQueriesUsePlanScopedProperties() {
    var query = new PartTree("findByTenantIdAndPropositionIdAndPlanId", PlanSaleOrchestrator.class);
    assertEquals(
        java.util.List.of("tenantId", "propositionId", "planId"),
        query.getParts().stream().map(p -> p.getProperty().toDotPath()).toList());
  }
}
