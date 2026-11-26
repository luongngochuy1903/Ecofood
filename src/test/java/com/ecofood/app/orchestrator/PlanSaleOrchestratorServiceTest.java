package com.ecofood.app.orchestrator;

import static com.ecofood.app.orchestrator.SaleFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.orchestrator.repository.PlanSaleOrchestratorRepository;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.orchestrator.service.PlanSaleOrchestratorServiceImpl;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.dto.PlanSaleOrchestratorRequest;
import com.ecofood.core.dto.SaleConfigurationField;
import com.ecofood.core.enums.PlanStatusEnum;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

class PlanSaleOrchestratorServiceTest {
  private final PlanSaleOrchestratorRepository repo = mock(PlanSaleOrchestratorRepository.class);
  private final PlanSaleAccess access = mock(PlanSaleAccess.class);
  private final CoreProductProcessClient productProcesses = mock(CoreProductProcessClient.class);
  private final PlanSaleOrchestratorServiceImpl service =
      new PlanSaleOrchestratorServiceImpl(repo, access, productProcesses);
  private final PropositionEntity proposition = new PropositionEntity();

  private PlanSaleAccess.PropositionScope scope(
      List<com.ecofood.app.proposition.entity.Plan> plans) {
    return new PlanSaleAccess.PropositionScope(TENANT, "employee", false, proposition, plans);
  }

  private PlanSaleAccess.PropositionScope enterpriseScope(
      List<com.ecofood.app.proposition.entity.Plan> plans) {
    return new PlanSaleAccess.PropositionScope("enterprise-tenant", "employee", true, proposition, plans);
  }

  private PlanSaleOrchestratorRequest request(Long expectedRevision, String name, List<String> planIds) {
    return request(expectedRevision, name, planIds, List.of());
  }

  private PlanSaleOrchestratorRequest request(
      Long expectedRevision, String name, List<String> planIds, List<SaleConfigurationField> additionalInfoFields) {
    return new PlanSaleOrchestratorRequest(expectedRevision, name, "desc", planIds, null, additionalInfoFields, null, false);
  }

  private PlanSaleOrchestratorRequest request(
      Long expectedRevision,
      String name,
      List<String> planIds,
      List<SaleConfigurationField> planItems,
      List<SaleConfigurationField> additionalInfoFields,
      List<SaleConfigurationField> invoiceHolder) {
    return new PlanSaleOrchestratorRequest(
        expectedRevision, name, "desc", planIds, planItems, additionalInfoFields, invoiceHolder, false);
  }

  @BeforeEach
  void setup() {
    proposition.setId(new ObjectId(PROP));
    when(repo.save(any()))
        .thenAnswer(
            inv -> {
              PlanSaleOrchestrator row = inv.getArgument(0);
              if (row.getId() == null) row.setId(new ObjectId().toHexString());
              row.setRevision(row.getRevision() == null ? 0L : row.getRevision() + 1);
              return row;
            });
  }

  @Test
  void createPersistsOrchestratorScopedToTenantAndPlans() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of(planA)));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    var response = service.create(PROP, request(null, "Sale A", List.of(PLAN)));
    assertEquals(PROP, response.propositionId());
    assertEquals("Sale A", response.name());
    assertEquals(1, response.plans().size());
    assertEquals(PLAN, response.plans().get(0).planId());
    assertEquals("Chicken Plan", response.plans().get(0).planName());
    var capture = org.mockito.ArgumentCaptor.forClass(PlanSaleOrchestrator.class);
    verify(repo).save(capture.capture());
    assertEquals(TENANT, capture.getValue().getTenantId());
    assertEquals(List.of(PLAN), capture.getValue().getPlanIds());
  }

  @Test
  void createCustomerEnabledLocksARequiredExternalIdInvoiceHolderField() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of(planA)));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    var request = new PlanSaleOrchestratorRequest(
        null, "Sale A", "desc", List.of(PLAN), null, null,
        List.of(new SaleConfigurationField("email", "STRING", "Email", "", false)), true);
    var response = service.create(PROP, request);
    assertTrue(response.createCustomer());
    var externalId = response.invoiceHolder().stream().filter(f -> f.key().equals("externalId")).findFirst().orElseThrow();
    assertTrue(externalId.required());
    assertEquals("STRING", externalId.dataType());
    // The submitted field list is otherwise preserved alongside the locked one.
    assertTrue(response.invoiceHolder().stream().anyMatch(f -> f.key().equals("email")));
  }

  @Test
  void createCustomerDisabledLeavesInvoiceHolderUntouched() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of(planA)));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    var response = service.create(PROP, request(null, "Sale A", List.of(PLAN)));
    assertFalse(response.createCustomer());
    assertTrue(response.invoiceHolder().stream().noneMatch(f -> f.key().equals("externalId")));
  }

  @Test
  void createRejectsPlanNotVisibleToTenant() {
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of()));
    assertThrows(
        BadRequestException.class, () -> service.create(PROP, request(null, "Sale", List.of(PLAN))));
    verify(repo, never()).save(any());
  }

  @Test
  void createRejectsPlanAlreadyClaimedByAnotherOrchestrator() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of(planA)));
    var existing = new PlanSaleOrchestrator();
    existing.setId("other-orch");
    when(repo.findByTenantIdAndPlanIdsContaining(TENANT, PLAN)).thenReturn(Optional.of(existing));
    assertThrows(
        BadRequestException.class, () -> service.create(PROP, request(null, "Sale", List.of(PLAN))));
    verify(repo, never()).save(any());
  }

  @Test
  void createRequiresNameAndAtLeastOnePlan() {
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of()));
    assertThrows(BadRequestException.class, () -> service.create(PROP, request(null, "Sale", List.of())));
    assertThrows(BadRequestException.class, () -> service.create(PROP, request(null, "", List.of(PLAN))));
  }

  @Test
  void updateRequiresMatchingExpectedRevisionAndAllowsReplacingPlans() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    var planB = plan(OTHER_PLAN, "PL00002", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of(planA, planB)));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    when(productProcesses.get(OTHER_PLAN)).thenReturn(processNamed(OTHER_PLAN, "Water Plan"));
    var created = service.create(PROP, request(null, "Sale A", List.of(PLAN)));
    var row = new PlanSaleOrchestrator();
    row.setId(created.id());
    row.setTenantId(TENANT);
    row.setPropositionId(PROP);
    row.setRevision(created.revision());
    row.setPlanIds(new ArrayList<>(List.of(PLAN)));
    when(repo.findByIdAndTenantIdAndPropositionId(created.id(), TENANT, PROP))
        .thenReturn(Optional.of(row));
    assertThrows(
        ResponseStatusException.class,
        () -> service.update(PROP, created.id(), request(-1L, "Sale A", List.of(PLAN))));
    var updated =
        service.update(PROP, created.id(), request(created.revision(), "Sale A2", List.of(OTHER_PLAN)));
    assertEquals("Sale A2", updated.name());
    assertEquals(List.of(OTHER_PLAN), updated.plans().stream().map(p -> p.planId()).toList());
  }

  @Test
  void getThrowsNotFoundWhenMissing() {
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of()));
    when(repo.findByIdAndPropositionId("missing", PROP)).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> service.get(PROP, "missing"));
  }

  @Test
  void directoryListsOneRowPerOrchestratorAndSupportsSearch() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    var planB = plan(OTHER_PLAN, "PL00002", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of(planA, planB)));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    when(productProcesses.get(OTHER_PLAN)).thenReturn(processNamed(OTHER_PLAN, "Water Plan"));
    var rowA = new PlanSaleOrchestrator();
    rowA.setId("orch-a");
    rowA.setName("Sale A");
    rowA.setPlanIds(List.of(PLAN));
    rowA.setUpdatedAt(Instant.now());
    var rowB = new PlanSaleOrchestrator();
    rowB.setId("orch-b");
    rowB.setName("Sale B");
    rowB.setPlanIds(List.of(OTHER_PLAN));
    rowB.setUpdatedAt(Instant.now());
    when(repo.findByPropositionId(PROP)).thenReturn(List.of(rowA, rowB));
    var page = service.directory(PROP, 0, 20, null, null);
    assertEquals(2, page.getContent().size());
    var filtered = service.directory(PROP, 0, 20, null, "chicken");
    assertEquals(1, filtered.getContent().size());
    assertEquals("orch-a", filtered.getContent().get(0).id());
    assertEquals(List.of("Chicken Plan"), filtered.getContent().get(0).planNames());
  }

  @Test
  void enterpriseActorSeesAndCanEditAnyTenantsOrchestratorAcrossTheProposition() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    var planB = plan(OTHER_PLAN, "PL00002", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(enterpriseScope(List.of(planA, planB)));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    when(productProcesses.get(OTHER_PLAN)).thenReturn(processNamed(OTHER_PLAN, "Water Plan"));
    var rowA = new PlanSaleOrchestrator();
    rowA.setId("orch-a");
    rowA.setTenantId(TENANT);
    rowA.setPropositionId(PROP);
    rowA.setName("Tenant's Orchestrator");
    rowA.setRevision(0L);
    rowA.setPlanIds(new ArrayList<>(List.of(PLAN)));
    rowA.setUpdatedAt(Instant.now());
    when(repo.findByPropositionId(PROP)).thenReturn(List.of(rowA));
    var page = service.directory(PROP, 0, 20, null, null);
    assertEquals(1, page.getContent().size());
    assertEquals("orch-a", page.getContent().get(0).id());

    when(repo.findByIdAndPropositionId("orch-a", PROP)).thenReturn(Optional.of(rowA));
    var detail = service.get(PROP, "orch-a");
    assertEquals("Tenant's Orchestrator", detail.name());

    var updated =
        service.update(PROP, "orch-a", request(0L, "Updated by Enterprise", List.of(OTHER_PLAN)));
    assertEquals("Updated by Enterprise", updated.name());
    // The uniqueness check must use the row's OWN tenant (TENANT), never the
    // Enterprise actor's own tenantId ("enterprise-tenant") — otherwise a
    // real collision within the owning Tenant's own Orchestrators could
    // slip through unnoticed.
    verify(repo).findByTenantIdAndPlanIdsContaining(TENANT, OTHER_PLAN);
    verify(repo, never()).findByTenantIdAndPlanIdsContaining("enterprise-tenant", OTHER_PLAN);
  }

  @Test
  void tenantActorCanViewButNotEditAnotherTenantsOrchestratorOnTheSameProposition() {
    // "Same Proposition" is the only scope for viewing — a Tenant that
    // didn't create this Orchestrator can still see it, just not edit it.
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of()));
    var ownedByOther = new PlanSaleOrchestrator();
    ownedByOther.setId("orch-a");
    ownedByOther.setTenantId("someone-else");
    ownedByOther.setPropositionId(PROP);
    ownedByOther.setName("Someone Else's Orchestrator");
    ownedByOther.setPlanIds(List.of());
    when(repo.findByIdAndPropositionId("orch-a", PROP)).thenReturn(Optional.of(ownedByOther));
    var detail = service.get(PROP, "orch-a");
    assertEquals("Someone Else's Orchestrator", detail.name());
    assertFalse(detail.canEdit());

    when(repo.findByIdAndTenantIdAndPropositionId("orch-a", TENANT, PROP))
        .thenReturn(Optional.empty());
    assertThrows(
        ResponseStatusException.class,
        () -> service.update(PROP, "orch-a", request(0L, "Hijacked", List.of())));
  }

  @Test
  void aTenantsOwnOrchestratorReportsCanEditTrue() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of(planA)));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    var response = service.create(PROP, request(null, "Sale A", List.of(PLAN)));
    assertTrue(response.canEdit());
  }

  @Test
  void createPersistsAndReturnsAdditionalInfoFields() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of(planA)));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    var giftNote = new SaleConfigurationField("giftNote", "STRING", "Gift note", "Printed on receipt", true);
    var response = service.create(PROP, request(null, "Sale A", List.of(PLAN), List.of(giftNote)));
    assertEquals(List.of(giftNote), response.additionalInfoFields());
    var capture = org.mockito.ArgumentCaptor.forClass(PlanSaleOrchestrator.class);
    verify(repo).save(capture.capture());
    assertEquals(List.of(giftNote), capture.getValue().getAdditionalInfoFields());
  }

  @Test
  void createRejectsAdditionalInfoFieldReusingAFixedItemKey() {
    when(access.currentForProposition(PROP))
        .thenReturn(scope(List.of(plan(PLAN, "PL00001", PlanStatusEnum.APPROVED))));
    var collides = new SaleConfigurationField("quantity", "NUMBER", "", "", false);
    assertThrows(
        BadRequestException.class,
        () -> service.create(PROP, request(null, "Sale", List.of(PLAN), List.of(collides))));
    verify(repo, never()).save(any());
  }

  @Test
  void createRejectsDuplicateAdditionalInfoKeysAndUnsupportedDataTypes() {
    when(access.currentForProposition(PROP))
        .thenReturn(scope(List.of(plan(PLAN, "PL00001", PlanStatusEnum.APPROVED))));
    var a = new SaleConfigurationField("note", "STRING", "", "", false);
    var duplicate = new SaleConfigurationField("note", "STRING", "", "", true);
    assertThrows(
        BadRequestException.class,
        () -> service.create(PROP, request(null, "Sale", List.of(PLAN), List.of(a, duplicate))));
    var unsupported = new SaleConfigurationField("note", "OBJECT", "", "", false);
    assertThrows(
        BadRequestException.class,
        () -> service.create(PROP, request(null, "Sale", List.of(PLAN), List.of(unsupported))));
    verify(repo, never()).save(any());
  }

  @Test
  void createDefaultsPlanItemsAndInvoiceHolderWhenNotSupplied() {
    var planA = plan(PLAN, "PL00001", PlanStatusEnum.APPROVED);
    when(access.currentForProposition(PROP)).thenReturn(scope(List.of(planA)));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    var response = service.create(PROP, request(null, "Sale A", List.of(PLAN)));
    assertEquals(
        Set.of("planId", "quantity", "actualPrice"),
        response.planItems().stream().map(SaleConfigurationField::key).collect(java.util.stream.Collectors.toSet()));
    assertTrue(response.planItems().stream().filter(f -> f.key().equals("planId")).findFirst().orElseThrow().required());
    assertTrue(response.planItems().stream().filter(f -> f.key().equals("quantity")).findFirst().orElseThrow().required());
    assertFalse(response.planItems().stream().filter(f -> f.key().equals("actualPrice")).findFirst().orElseThrow().required());
    assertEquals(
        Set.of("firstName", "lastName", "email", "phoneNumber", "address"),
        response.invoiceHolder().stream().map(SaleConfigurationField::key).collect(java.util.stream.Collectors.toSet()));
    assertTrue(response.invoiceHolder().stream().noneMatch(SaleConfigurationField::required));
  }

  @Test
  void createAllowsTogglingActualPriceRequiredButRejectsRenamingPlanItemKeys() {
    when(access.currentForProposition(PROP))
        .thenReturn(scope(List.of(plan(PLAN, "PL00001", PlanStatusEnum.APPROVED))));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    var planItems =
        List.of(
            new SaleConfigurationField("planId", "STRING", "", "", true),
            new SaleConfigurationField("quantity", "NUMBER", "", "", true),
            new SaleConfigurationField("actualPrice", "NUMBER", "", "", true));
    var response = service.create(PROP, request(null, "Sale", List.of(PLAN), planItems, List.of(), List.of()));
    assertTrue(response.planItems().stream().filter(f -> f.key().equals("actualPrice")).findFirst().orElseThrow().required());

    var renamed =
        List.of(
            new SaleConfigurationField("planId", "STRING", "", "", true),
            new SaleConfigurationField("qty", "NUMBER", "", "", true),
            new SaleConfigurationField("actualPrice", "NUMBER", "", "", false));
    assertThrows(
        BadRequestException.class,
        () -> service.create(PROP, request(null, "Sale", List.of(PLAN), renamed, List.of(), List.of())));
  }

  @Test
  void createRejectsDuplicateInvoiceHolderKeysAndUnsupportedDataTypes() {
    when(access.currentForProposition(PROP))
        .thenReturn(scope(List.of(plan(PLAN, "PL00001", PlanStatusEnum.APPROVED))));
    var duplicate =
        List.of(
            new SaleConfigurationField("firstName", "STRING", "", "", false),
            new SaleConfigurationField("firstName", "STRING", "", "", false));
    assertThrows(
        BadRequestException.class,
        () -> service.create(PROP, request(null, "Sale", List.of(PLAN), null, List.of(), duplicate)));
    var unsupported = List.of(new SaleConfigurationField("firstName", "OBJECT", "", "", false));
    assertThrows(
        BadRequestException.class,
        () -> service.create(PROP, request(null, "Sale", List.of(PLAN), null, List.of(), unsupported)));
    verify(repo, never()).save(any());
  }

  @Test
  void handlesConcurrentCreateConflicts() {
    when(access.currentForProposition(PROP))
        .thenReturn(scope(List.of(plan(PLAN, "PL00001", PlanStatusEnum.APPROVED))));
    when(productProcesses.get(PLAN)).thenReturn(processNamed(PLAN, "Chicken Plan"));
    doThrow(new DuplicateKeyException("race")).when(repo).save(any());
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () -> service.create(PROP, request(null, "Sale", List.of(PLAN))))
            .getStatusCode()
            .value());
  }
}
