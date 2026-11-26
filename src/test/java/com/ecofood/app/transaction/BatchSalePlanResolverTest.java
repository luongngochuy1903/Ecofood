package com.ecofood.app.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.customer.security.CustomerIdentity;
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
  private final CustomerIdentity identity = mock(CustomerIdentity.class);
  private final BatchSalePlanResolver resolver =
      new BatchSalePlanResolver(repository, access, snapshots, productProcesses, identity);

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
  void matchCustomerIsEmptyWhenCreateCustomerIsDisabled() {
    var row = row();
    row.setCreateCustomer(false);
    when(repository.findById("orch")).thenReturn(Optional.of(row));
    assertEquals(Optional.empty(), resolver.matchCustomer("tenant", "orch", Map.of("externalId", "ext-1")));
    verifyNoInteractions(identity);
  }

  @Test
  void matchCustomerIsEmptyWhenExternalIdIsMissingOrBlank() {
    var row = row();
    row.setCreateCustomer(true);
    when(repository.findById("orch")).thenReturn(Optional.of(row));
    assertEquals(Optional.empty(), resolver.matchCustomer("tenant", "orch", Map.of()));
    assertEquals(Optional.empty(), resolver.matchCustomer("tenant", "orch", null));
    assertEquals(Optional.empty(), resolver.matchCustomer("tenant", "orch", Map.of("externalId", "  ")));
    verifyNoInteractions(identity);
  }

  @Test
  void matchCustomerDelegatesToCustomerIdentityWhenCreateCustomerIsEnabled() {
    var row = row();
    row.setCreateCustomer(true);
    when(repository.findById("orch")).thenReturn(Optional.of(row));
    when(identity.matchByExternalId("tenant", "ext-1")).thenReturn(Optional.of("customer-1"));
    assertEquals(Optional.of("customer-1"), resolver.matchCustomer("tenant", "orch", Map.of("externalId", "ext-1")));
  }

  @Test
  void usesEnterpriseOrchestratorForAssignedPartnerWithoutChangingSaleTenant() {
    var scope = stubScope();
    when(repository.findByTenantIdAndPlanIdsContaining("tenant", "plan"))
        .thenReturn(Optional.empty());
    var enterprise = row();
    enterprise.setTenantId("enterprise");
    when(repository.findByPlanIdsContaining("plan")).thenReturn(List.of(enterprise));
    when(access.canUseEnterpriseConfiguration("tenant", "prop", "plan", "enterprise"))
        .thenReturn(true);
    stubPlanName("Plan name");
    when(snapshots.capture(eq("tenant"), any(), any())).thenReturn(mock(InvoiceSnapshot.class));

    var line = resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now());

    assertEquals("orch", line.orchestratorId());
    var captured = ArgumentCaptor.forClass(PreparedSaleTransaction.class);
    verify(snapshots).capture(eq("tenant"), captured.capture(), any());
    assertEquals("tenant", captured.getValue().binding().tenantId());
    assertEquals("tenant", captured.getValue().binding().distributionPartnerId());
    verify(access).active(scope);
  }

  @Test
  void rejectsOrchestratorOwnedByAnotherPartnerOrUnassignedEnterprise() {
    var foreign = row();
    foreign.setTenantId("other-partner");
    when(repository.findByPlanIdsContaining("plan")).thenReturn(List.of(foreign));
    assertThrows(BadRequestException.class, () -> resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now()));
    verifyNoInteractions(snapshots, productProcesses);
    verify(access, never()).active(any());
  }

  @Test
  void enterpriseConfigurationStillEnforcesRequiredAdditionalInfo() {
    var scope = stubScope();
    when(repository.findByTenantIdAndPlanIdsContaining("tenant", "plan"))
        .thenReturn(Optional.empty());
    var enterprise = row(new SaleConfigurationField("note", "STRING", "", "", true));
    enterprise.setTenantId("enterprise");
    when(repository.findByPlanIdsContaining("plan")).thenReturn(List.of(enterprise));
    when(access.canUseEnterpriseConfiguration("tenant", "prop", "plan", "enterprise"))
        .thenReturn(true);
    assertThrows(BadRequestException.class, () -> resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now()));
    verify(access).active(scope);
    verifyNoInteractions(snapshots);
  }

  @Test
  void selectsLatestAuthorizedConfigurationRegardlessOfOwnerAndRepositoryOrder() {
    stubScope();
    stubPlanName("Plan name");
    when(repository.findByPlanIdsContaining("plan")).thenReturn(List.of(row()));
    resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now());

    var enterprise = row();
    enterprise.setId("enterprise-orch");
    enterprise.setTenantId("enterprise");
    enterprise.setCreatedAt(Instant.parse("2026-11-25T10:00:00Z"));
    when(repository.findByPlanIdsContaining("plan")).thenReturn(List.of(enterprise, row()));
    when(access.canUseEnterpriseConfiguration("tenant", "prop", "plan", "enterprise"))
        .thenReturn(true);
    assertEquals("enterprise-orch", resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now()).orchestratorId());

    var own = row();
    own.setCreatedAt(Instant.parse("2026-11-24T10:00:00Z"));
    own.setUpdatedAt(Instant.parse("2026-11-25T11:00:00Z"));
    when(repository.findByTenantIdAndPlanIdsContaining("tenant", "plan"))
        .thenReturn(Optional.of(own));
    when(repository.findByPlanIdsContaining("plan")).thenReturn(List.of(own, enterprise));
    assertEquals("orch", resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now()).orchestratorId());

    // A newer enterprise configuration must also supply the validation rules actually applied.
    enterprise.setUpdatedAt(Instant.parse("2026-11-25T12:00:00Z"));
    enterprise.setAdditionalInfoFields(List.of(
        new SaleConfigurationField("note", "STRING", "", "", true)));
    assertThrows(BadRequestException.class, () -> resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now()));
  }

  @Test
  void ignoresNewerUnauthorizedConfigurationAndBreaksTimestampTiesConsistently() {
    stubScope();
    stubPlanName("Plan name");
    var enterprise = row();
    enterprise.setId("z-enterprise-orch");
    enterprise.setTenantId("enterprise");
    enterprise.setUpdatedAt(Instant.parse("2026-11-25T12:00:00Z"));
    when(repository.findByPlanIdsContaining("plan")).thenReturn(List.of(enterprise));
    assertEquals("orch", resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now()).orchestratorId());

    enterprise.setUpdatedAt(null);
    when(access.canUseEnterpriseConfiguration("tenant", "prop", "plan", "enterprise"))
        .thenReturn(true);
    when(repository.findByPlanIdsContaining("plan")).thenReturn(List.of(enterprise, row()));
    assertEquals("z-enterprise-orch", resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now()).orchestratorId());
    when(repository.findByPlanIdsContaining("plan")).thenReturn(List.of(row(), enterprise));
    assertEquals("z-enterprise-orch", resolver.resolve("tenant", "seller", 0,
        new Item("plan", 2, BigDecimal.TEN), Instant.now()).orchestratorId());
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
