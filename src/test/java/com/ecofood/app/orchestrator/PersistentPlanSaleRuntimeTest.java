package com.ecofood.app.orchestrator;

import static com.ecofood.app.orchestrator.SaleFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.*;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.orchestrator.repository.PlanSaleOrchestratorRepository;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.orchestrator.service.PersistentPlanSaleRuntime;
import com.ecofood.app.orchestrator.validation.SaleSchemaValidator;
import com.ecofood.core.dto.PlanSaleOrchestratorDtos;
import com.ecofood.core.enums.PlanSaleOrchestratorStatusEnum;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class PersistentPlanSaleRuntimeTest {
  private final PlanSaleOrchestratorRepository repo = mock(PlanSaleOrchestratorRepository.class);
  private final PlanSaleAccess access = mock(PlanSaleAccess.class);
  private final jakarta.validation.ValidatorFactory factory =
      Validation.buildDefaultValidatorFactory();
  private final PersistentPlanSaleRuntime runtime =
      new PersistentPlanSaleRuntime(
          repo, access, new SaleSchemaValidator(), factory.getValidator());
  private PlanSaleOrchestrator row;

  @BeforeEach
  void setup() {
    row = row(PLAN);
    when(repo.findByIdAndTenantId("orch", TENANT)).thenReturn(Optional.of(row));
    when(access.require(eq(TENANT), eq(PROP), any())).thenAnswer(inv -> scope(inv.getArgument(2)));
    when(access.currency(any())).thenReturn("VND");
  }

  @AfterEach
  void close() {
    factory.close();
  }

  private PlanSaleOrchestrator row(String plan) {
    var r = new PlanSaleOrchestrator();
    r.setId("orch");
    r.setTenantId(TENANT);
    r.setPropositionId(PROP);
    r.setPlanId(plan);
    r.setActiveVersion(1);
    r.getVersions()
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
    r.getVersions()
        .add(
            new PlanSaleOrchestratorDtos.ConfigVersion(
                2,
                PlanSaleOrchestratorStatusEnum.DRAFT,
                config(),
                Instant.now(),
                "employee",
                Instant.now(),
                "employee",
                null,
                null));
    return r;
  }

  @Test
  void preparesOneActiveVersionWithTrustedPlanIdentityAndOriginalUc015Dto() {
    var result = runtime.prepare(TENANT, "pos-subject", "orch", payload());
    assertEquals(1, result.orchestratorVersion());
    assertEquals(PLAN, result.binding().planId());
    assertEquals(TENANT, result.binding().tenantId());
    assertEquals(TENANT, result.binding().distributionPartnerId());
    assertEquals("pos-subject", result.binding().posId());
    assertEquals("INV-1", result.transaction().invoiceNumber());
    assertTrue(result.postWorkflows().isEmpty());
    verify(access).active(any());
  }

  @Test
  void rejectsCrossTenantOrMissingIdentityAndPayloadIdentityOverride() {
    assertThrows(
        ForbiddenException.class, () -> runtime.prepare("other", "pos-subject", "orch", payload()));
    assertThrows(ForbiddenException.class, () -> runtime.prepare(TENANT, "", "orch", payload()));
    var injected = payload();
    injected.put("planId", OTHER_PLAN);
    assertThrows(
        BadRequestException.class, () -> runtime.prepare(TENANT, "pos-subject", "orch", injected));
  }

  @Test
  void acceptsVerifiedTenantCallerWithoutManualPosBinding() {
    var result = runtime.prepare(TENANT, "verified-employee", "orch", payload());
    assertEquals("verified-employee", result.binding().posId());
    assertEquals(PLAN, result.binding().planId());
  }

  @Test
  void supportsSamePosAcrossDifferentPlansWithoutImplicitlyChoosingOne() {
    when(repo.findByTenantIdAndActiveVersionNotNull(TENANT))
        .thenReturn(List.of(row, row(OTHER_PLAN)));
    assertThrows(IllegalStateException.class, () -> runtime.findForPos(TENANT, "pos-subject"));
    assertEquals(
        PLAN, runtime.prepare(TENANT, "pos-subject", "orch", payload()).binding().planId());
  }

  @Test
  void rejectsInvalidCurrencyAndNestedUc015Constraint() {
    var wrong = payload();
    wrong.put("currency", "USD");
    assertThrows(
        BadRequestException.class, () -> runtime.prepare(TENANT, "pos-subject", "orch", wrong));
    var quantity = payload();
    quantity.put(
        "items",
        List.of(
            Map.of(
                "productCode",
                "P1",
                "productName",
                "Milk",
                "quantity",
                0,
                "unitPrice",
                10,
                "amount",
                0)));
    assertThrows(
        BadRequestException.class, () -> runtime.prepare(TENANT, "pos-subject", "orch", quantity));
  }

  @Test
  void inactiveOrExpiredPlanCannotPrepareEvenWhenConfigurationWasActivated() {
    doThrow(new IllegalStateException("Plan expired")).when(access).active(any());
    assertThrows(
        IllegalStateException.class,
        () -> runtime.prepare(TENANT, "pos-subject", "orch", payload()));
  }
}
