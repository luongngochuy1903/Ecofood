package com.ecofood.app.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.transaction.service.InvoiceSnapshotFactory;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.dto.PreparedSaleTransaction;
import com.ecofood.core.dto.ProductCostingAttributeResponse;
import com.ecofood.core.dto.ProductProcessElementResponse;
import com.ecofood.core.dto.ProductProcessResponse;
import com.ecofood.core.dto.PropositionDefinition;
import com.ecofood.core.dto.SaleOrchestratorConfig;
import com.ecofood.core.dto.SubmitTransactionRequest;
import com.ecofood.core.enums.ProductStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class InvoiceSnapshotFactoryTest {
  private static final String TENANT = "111111111111111111111111";
  private static final String PROP = "222222222222222222222222";
  private static final String PLAN = "333333333333333333333333";
  private static final String PRODUCT = "444444444444444444444444";
  private static final String PROCESS = "555555555555555555555555";
  private final PlanSaleAccess access = mock(PlanSaleAccess.class);
  private final CoreProductProcessClient productProcesses = mock(CoreProductProcessClient.class);
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final InvoiceSnapshotFactory factory = new InvoiceSnapshotFactory(access, productProcesses, mongo);
  private PropositionEntity proposition;
  private PreparedSaleTransaction prepared;

  @BeforeEach
  void setup() {
    proposition = new PropositionEntity();
    proposition.setId(new ObjectId(PROP));
    proposition.setRevision(8L);
    proposition.setProductId(new ObjectId(PRODUCT));
    proposition.setDefinition(definition("10"));
    var plan = new Plan();
    plan.setId(new ObjectId(PLAN));
    plan.setVersion(3);
    plan.setProductVersion(2);
    when(productProcesses.get(PLAN)).thenReturn(process("VND"));
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("product_process")))
        .thenReturn(new Document("_id", new ObjectId(PROCESS)));
    when(access.require(TENANT, PROP, PLAN))
        .thenReturn(new PlanSaleAccess.Scope(TENANT, null, proposition, plan));
    prepared = new PreparedSaleTransaction("orch", 4,
        new SaleOrchestratorConfig(TENANT, "pos", TENANT, PLAN, PROP,
            true, true, true, Set.of("VND"), 100),
        new SubmitTransactionRequest("INV1", Instant.parse("2020-01-01T00:00:00Z"), "VND",
            bd("115"), List.of(new InvoiceItemDto("P", "Product", BigDecimal.ONE, bd("115"), bd("115")))),
        Map.of("netRevenue", 999, "ingredientCost", 999), Set.of());
  }

  private ProductProcessResponse process(String currency) {
    var costing = new ProductCostingAttributeResponse("attribute", PROCESS, 6L,
        bd("40"), bd("5"), bd("10"), bd("3"), bd("2"), bd("30"), bd("10"),
        bd("60"), bd("94.29"), bd("100"), currency, null);
    return new ProductProcessResponse(PROCESS, PRODUCT, 2, 6L, ProductStatusEnum.APPROVED,
        "Pinned version 2", null,
        new ProductProcessElementResponse<>("costing", "Costing", List.of(costing)),
        null, false, null, null, null, null, null);
  }

  private PropositionDefinition definition(String platformShare) {
    return new PropositionDefinition("Proposition", null, List.of("VN"), null, List.of(TENANT),
        "VND", null, null, null, List.of(), List.of(), bd("60"), bd(platformShare), bd("30"),
        Set.of(), 2026, null);
  }

  @Test
  void capturesPinnedCostingSharesPlanAndSaleVersionWithoutInventingNetRevenue() {
    var receivedAt = Instant.parse("2026-12-31T17:00:00Z");
    var snapshot = factory.capture(TENANT, prepared, receivedAt);
    assertEquals(PROP, snapshot.propositionId());
    assertEquals(8L, snapshot.propositionRevision());
    assertEquals(PLAN, snapshot.planId());
    assertEquals(3, snapshot.planVersion());
    assertEquals("orch", snapshot.orchestratorId());
    assertEquals(4, snapshot.orchestratorVersion());
    assertEquals(PRODUCT, snapshot.productId());
    assertEquals(PROCESS, snapshot.productProcessId());
    assertEquals(2, snapshot.productVersion());
    assertEquals(6L, snapshot.productProcessRevision());
    assertEquals("attribute", snapshot.costing().attributeId());
    assertEquals(bd("40"), snapshot.costing().ingredientCost());
    assertEquals(bd("5"), snapshot.costing().packagingCost());
    assertEquals(bd("10"), snapshot.costing().laborCost());
    assertEquals(bd("3"), snapshot.costing().operationCost());
    assertEquals(bd("2"), snapshot.costing().marketingFee());
    assertEquals(bd("10"), snapshot.profitShare().platformProfitShare());
    // Plan no longer carries a separate market-configuration layer — its
    // costing (asserted above) is the one pricing source now.
    assertNull(snapshot.market());
    assertEquals("Asia/Ho_Chi_Minh", snapshot.accountingTimezone());
    assertEquals(1, snapshot.accountingMonth());
    assertEquals(2027, snapshot.accountingYear());
    assertEquals(receivedAt, snapshot.capturedAt());
    assertEquals(List.of("NET_REVENUE_UNVERIFIED"), snapshot.missingData());
    assertEquals("MISSING_DATA", snapshot.kpiCalculationStatus());
    assertNull(snapshot.netRevenue());
    assertNull(snapshot.platformCost());
    assertNull(snapshot.profit());
    // Replacing the source after capture cannot change an immutable snapshot.
    proposition.setDefinition(definition("25"));
    assertEquals(bd("10"), snapshot.profitShare().platformProfitShare());
    assertEquals(bd("40"), snapshot.costing().ingredientCost());
    var missingData = snapshot.missingData();
    assertThrows(UnsupportedOperationException.class, missingData::clear);
  }

  @Test
  void recordsMissingOrMismatchedCostWithoutSubstitutingZeroOrDoingCurrencyConversion() {
    when(productProcesses.get(PLAN)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));
    var missing = factory.capture(TENANT, prepared, Instant.now());
    assertNull(missing.costing());
    assertTrue(missing.missingData().contains("PRODUCT_PROCESS_MISSING"));
    doReturn(process("USD")).when(productProcesses).get(PLAN);
    var mismatch = factory.capture(TENANT, prepared, Instant.now());
    assertEquals("USD", mismatch.costing().currency());
    assertEquals("VND", mismatch.currency());
    assertTrue(mismatch.missingData().contains("COSTING_CURRENCY_MISMATCH"));
    assertNull(mismatch.profit());
  }

  @Test
  void sourceMetadataLookupUsesOnlyTheAuthorizedProductsBoundVersion() {
    factory.capture(TENANT, prepared, Instant.now());
    var query = org.mockito.ArgumentCaptor.forClass(Query.class);
    verify(mongo).findOne(query.capture(), eq(Document.class), eq("product_process"));
    assertEquals(new ObjectId(PRODUCT), query.getValue().getQueryObject().get("product_identifier"));
    assertEquals(2, query.getValue().getQueryObject().get("version"));
  }

  @Test
  void sourceMetadataSupportsLegacyProcessesAndMissingReferences() {
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("product_process")))
        .thenReturn(null);
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("product_version")))
        .thenReturn(new Document("_id", new ObjectId(PROCESS)));
    assertEquals(PROCESS, factory.capture(TENANT, prepared, Instant.now()).productProcessId());
    when(mongo.findOne(any(Query.class), eq(Document.class), eq("product_version")))
        .thenReturn(null);
    assertNull(factory.capture(TENANT, prepared, Instant.now()).productProcessId());
  }

  @Test
  void rejectsUndistributedTenantBeforeReadingSnapshotData() {
    when(access.require(TENANT, PROP, PLAN))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "not distributed"));
    assertThrows(ResponseStatusException.class,
        () -> factory.capture(TENANT, prepared, Instant.now()));
    verifyNoInteractions(mongo);
    verify(productProcesses, never()).get(anyString());
  }

  @Test
  void rejectsOtherTenantBeforeLookingUpAnySource() {
    assertThrows(ForbiddenException.class, () -> factory.capture("other", prepared, Instant.now()));
    verify(access, never()).require(eq("other"), any(), any());
    verifyNoInteractions(mongo);
  }

  private BigDecimal bd(String value) { return new BigDecimal(value); }
}
