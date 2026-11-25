package com.ecofood.app.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.orchestrator.repository.PlanSaleOrchestratorRepository;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.orchestrator.service.BatchSalePlanResolver;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.transaction.dto.BatchTransactionDtos.Item;
import com.ecofood.app.transaction.entity.InvoiceSnapshot;
import com.ecofood.app.transaction.service.InvoiceSnapshotFactory;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.dto.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchSalePlanResolverTest {
  private final PlanSaleOrchestratorRepository repository = mock(PlanSaleOrchestratorRepository.class);
  private final PlanSaleAccess access = mock(PlanSaleAccess.class);
  private final InvoiceSnapshotFactory snapshots = mock(InvoiceSnapshotFactory.class);
  private final CoreProductProcessClient productProcesses = mock(CoreProductProcessClient.class);
  private final BatchSalePlanResolver resolver =
      new BatchSalePlanResolver(repository, access, snapshots, productProcesses);

  private PlanSaleOrchestrator row(SaleConfigurationField... additionalInfoFields) {
    var row = new PlanSaleOrchestrator();
    row.setId("orch");
    row.setTenantId("tenant");
    row.setPropositionId("prop");
    row.setPlanIds(List.of("plan"));
    row.setAdditionalInfoFields(List.of(additionalInfoFields));
    return row;
  }

  private PlanSaleAccess.Scope stubScope(SaleConfigurationField... additionalInfoFields) {
    var plan = new Plan();
    plan.setId(new org.bson.types.ObjectId());
    var scope = new PlanSaleAccess.Scope("tenant", null, null, plan);
    when(repository.findByTenantIdAndPlanIdsContaining("tenant", "plan"))
        .thenReturn(Optional.of(row(additionalInfoFields)));
    when(access.require("tenant", "prop", "plan")).thenReturn(scope);
    when(access.currency(scope)).thenReturn("VND");
    return scope;
  }

  private void stubPlanName(String name) {
    var definitionAttr =
        new ProductDefinitionAttributeResponse("def", "plan", 1L, name, null, null, null, null, List.of(), null);
    var content =
        new ProductProcessResponse(
            "plan", "fam", 1, 1L, null, null,
            new ProductProcessElementResponse<>("def", "Definition", List.of(definitionAttr)),
            null, null, false, null, null, null, null, null);
    when(productProcesses.get("plan")).thenReturn(content);
  }

  @Test
  void resolvesUsingThePlanOwningOrchestratorWithoutInventingSaleTime() {
    var scope = stubScope();
    stubPlanName("Plan name");
    var snapshot = mock(InvoiceSnapshot.class);
    when(snapshots.capture(eq("tenant"), any(), any())).thenReturn(snapshot);
    var now = Instant.now();
    var item = new Item("plan", new BigDecimal("2"), BigDecimal.TEN, null);
    var line = resolver.resolve("tenant", "seller", 0, item, now);
    assertEquals(snapshot, line.snapshot());
    assertEquals(2, line.quantity());
    assertEquals("item-0", line.lineId());
    assertEquals("orch", line.orchestratorId());
    assertEquals("Plan name", line.planName());
    assertEquals(BigDecimal.TEN, line.unitPrice());
    assertEquals(new BigDecimal("20"), line.amount());
    assertTrue(line.saleConfigurationVerified());
    var captured = ArgumentCaptor.forClass(PreparedSaleTransaction.class);
    verify(snapshots).capture(eq("tenant"), captured.capture(), eq(now));
    assertEquals("VND", captured.getValue().transaction().currency());
    assertNull(captured.getValue().transaction().transactionTime());
    verify(access).active(scope);
  }

  @Test
  void fallsBackToPlanSellingPriceWhenActualPriceOmitted() {
    var scope = stubScope();
    when(access.sellingPrice(scope)).thenReturn(new BigDecimal("15"));
    stubPlanName("Plan name");
    when(snapshots.capture(eq("tenant"), any(), any())).thenReturn(mock(InvoiceSnapshot.class));
    var item = new Item("plan", new BigDecimal("2"), null, null);
    var line = resolver.resolve("tenant", "seller", 0, item, Instant.now());
    assertEquals(new BigDecimal("15"), line.unitPrice());
    assertEquals(new BigDecimal("30"), line.amount());
    assertFalse(line.saleConfigurationVerified());
  }

  @Test
  void rejectsPlanWithNoSellingPriceAndNoActualPrice() {
    var scope = stubScope();
    when(access.sellingPrice(scope)).thenReturn(null);
    var item = new Item("plan", new BigDecimal("2"), null, null);
    assertThrows(
        BadRequestException.class,
        () -> resolver.resolve("tenant", "seller", 0, item, Instant.now()));
    verifyNoInteractions(snapshots);
  }

  @Test
  void requiredAdditionalInfoFieldMustBePresent() {
    var giftNote = new SaleConfigurationField("giftNote", "STRING", "", "", true);
    stubScope(giftNote);
    stubPlanName("Plan name");
    var item = new Item("plan", new BigDecimal("2"), BigDecimal.TEN, null);
    assertThrows(
        BadRequestException.class,
        () -> resolver.resolve("tenant", "seller", 0, item, Instant.now()));
    verifyNoInteractions(snapshots);
  }

  @Test
  void additionalInfoIsCapturedOnTheLineWhenRequiredFieldIsPresent() {
    var giftNote = new SaleConfigurationField("giftNote", "STRING", "", "", true);
    stubScope(giftNote);
    stubPlanName("Plan name");
    when(snapshots.capture(eq("tenant"), any(), any())).thenReturn(mock(InvoiceSnapshot.class));
    var item = new Item("plan", new BigDecimal("2"), BigDecimal.TEN, Map.of("giftNote", "Happy birthday"));
    var line = resolver.resolve("tenant", "seller", 0, item, Instant.now());
    assertEquals(Map.of("giftNote", "Happy birthday"), line.additionalInfo());
  }

  @Test
  void optionalAdditionalInfoFieldMayBeOmitted() {
    var giftNote = new SaleConfigurationField("giftNote", "STRING", "", "", false);
    stubScope(giftNote);
    stubPlanName("Plan name");
    when(snapshots.capture(eq("tenant"), any(), any())).thenReturn(mock(InvoiceSnapshot.class));
    var item = new Item("plan", new BigDecimal("2"), BigDecimal.TEN, null);
    var line = resolver.resolve("tenant", "seller", 0, item, Instant.now());
    assertNull(line.additionalInfo());
  }

  @Test
  void actualPriceRequiredByPlanItemsRejectsItemsMissingItWithNoFallback() {
    var scope = stubScope();
    var row = row();
    row.setPlanItems(List.of(new SaleConfigurationField("actualPrice", "NUMBER", "", "", true)));
    when(repository.findByTenantIdAndPlanIdsContaining("tenant", "plan")).thenReturn(Optional.of(row));
    when(access.sellingPrice(scope)).thenReturn(new BigDecimal("15"));
    var item = new Item("plan", new BigDecimal("2"), null, null);
    assertThrows(
        BadRequestException.class, () -> resolver.resolve("tenant", "seller", 0, item, Instant.now()));
    verifyNoInteractions(snapshots);
  }

  @Test
  void requireInvoiceHolderRejectsMissingRequiredFieldAndAcceptsWhenPresent() {
    var row = row();
    row.setInvoiceHolder(List.of(new SaleConfigurationField("email", "STRING", "", "", true)));
    when(repository.findById("orch")).thenReturn(Optional.of(row));
    assertThrows(
        BadRequestException.class, () -> resolver.requireInvoiceHolder("orch", Map.of()));
    assertThrows(
        BadRequestException.class, () -> resolver.requireInvoiceHolder("orch", null));
    resolver.requireInvoiceHolder("orch", Map.of("email", "a@b.com"));
  }

  @Test
  void requireInvoiceHolderIsANoOpWhenOrchestratorIsMissing() {
    when(repository.findById("missing")).thenReturn(Optional.empty());
    resolver.requireInvoiceHolder("missing", Map.of());
  }

  @Test
  void planNotConfiguredForSaleIsRejected() {
    when(repository.findByTenantIdAndPlanIdsContaining("tenant", "plan")).thenReturn(Optional.empty());
    var item = new Item("plan", new BigDecimal("2"), BigDecimal.TEN, null);
    assertThrows(
        BadRequestException.class,
        () -> resolver.resolve("tenant", "seller", 0, item, Instant.now()));
    verifyNoInteractions(snapshots);
  }
}
