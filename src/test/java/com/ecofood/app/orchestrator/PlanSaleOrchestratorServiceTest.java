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
import com.ecofood.app.orchestrator.validation.SaleSchemaValidator;
import com.ecofood.core.dto.PlanSaleOrchestratorDtos;
import com.ecofood.core.enums.PlanSaleOrchestratorStatusEnum;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.web.server.ResponseStatusException;

class PlanSaleOrchestratorServiceTest {
  private final PlanSaleOrchestratorRepository repo = mock(PlanSaleOrchestratorRepository.class);
  private final PlanSaleAccess access = mock(PlanSaleAccess.class);
  private final SaleSchemaValidator schemas = new SaleSchemaValidator();
  private final PlanSaleOrchestratorServiceImpl service =
      new PlanSaleOrchestratorServiceImpl(repo, access, schemas);
  private final Map<String, PlanSaleOrchestrator> rows = new HashMap<>();

  @BeforeEach
  void setup() {
    for (String plan : List.of(PLAN, OTHER_PLAN)) {
      when(access.current(PROP, plan)).thenReturn(scope(plan));
      when(repo.findByTenantIdAndPropositionIdAndPlanId(TENANT, PROP, plan))
          .thenAnswer(inv -> Optional.ofNullable(rows.get(plan)));
    }
    when(access.currency(any())).thenReturn("VND");
    when(repo.save(any()))
        .thenAnswer(
            inv -> {
              PlanSaleOrchestrator row = inv.getArgument(0);
              row.setId(row.getPlanId());
              row.setRevision(row.getRevision() == null ? 0L : row.getRevision() + 1);
              rows.put(row.getPlanId(), row);
              return row;
            });
  }

  private PlanSaleOrchestrator create(String plan) {
    return service.create(PROP, plan, new PlanSaleOrchestratorDtos.Save(null, config()));
  }

  private PlanSaleOrchestratorDtos.Revision revision(PlanSaleOrchestrator row) {
    return new PlanSaleOrchestratorDtos.Revision(row.getRevision());
  }

  @Test
  void createsExactlyOneDraftForEachPlanWithFixedContext() {
    var a = create(PLAN);
    var b = create(OTHER_PLAN);
    assertEquals(TENANT, a.getTenantId());
    assertEquals(PROP, a.getPropositionId());
    assertEquals(PLAN, a.getPlanId());
    assertEquals(OTHER_PLAN, b.getPlanId());
    assertNull(a.getActiveVersion());
    assertEquals(PlanSaleOrchestratorStatusEnum.DRAFT, a.getVersions().get(0).status());
    assertThrows(ResponseStatusException.class, () -> create(PLAN));
  }

  @Test
  void lifecycleKeepsOldActiveAndOtherPlanUnchanged() {
    var a = create(PLAN);
    var b = create(OTHER_PLAN);
    service.activate(PROP, PLAN, revision(a));
    service.version(PROP, PLAN, revision(a));
    assertEquals(1, a.getActiveVersion());
    assertEquals(PlanSaleOrchestratorStatusEnum.ACTIVE, a.getVersions().get(0).status());
    service.edit(PROP, PLAN, new PlanSaleOrchestratorDtos.Save(a.getRevision(), config()));
    service.activate(PROP, PLAN, revision(a));
    assertEquals(2, a.getActiveVersion());
    assertEquals(PlanSaleOrchestratorStatusEnum.ARCHIVED, a.getVersions().get(0).status());
    assertEquals(1, a.getVersions().stream().filter(v -> v.status() == PlanSaleOrchestratorStatusEnum.ACTIVE).count());
    assertEquals(0L, b.getRevision());
    assertNull(b.getActiveVersion());
    assertEquals(1, b.getVersions().size());
  }

  @Test
  void activeIsReadOnlyAndOnlyOneDraftIsAllowed() {
    var a = create(PLAN);
    assertThrows(ResponseStatusException.class, () -> service.version(PROP, PLAN, revision(a)));
    service.activate(PROP, PLAN, revision(a));
    assertThrows(
        ResponseStatusException.class,
        () -> service.edit(PROP, PLAN, new PlanSaleOrchestratorDtos.Save(a.getRevision(), config())));
  }

  @Test
  void invalidActivationKeepsCurrentActiveAndDoesNotSave() {
    var a = create(PLAN);
    service.activate(PROP, PLAN, revision(a));
    service.version(PROP, PLAN, revision(a));
    var invalid = new PlanSaleOrchestratorDtos.Configuration("Sale", "", 100, null, Set.of());
    service.edit(PROP, PLAN, new PlanSaleOrchestratorDtos.Save(a.getRevision(), invalid));
    clearInvocations(repo);
    assertFalse(service.validate(PROP, PLAN, revision(a)).valid());
    assertThrows(BadRequestException.class, () -> service.activate(PROP, PLAN, revision(a)));
    assertEquals(1, a.getActiveVersion());
    verify(repo, never()).save(any());
  }

  @Test
  void activatesPlanConfigurationWithoutAnyPosRegistry() {
    var a = create(PLAN);
    var b = create(OTHER_PLAN);
    service.activate(PROP, PLAN, revision(a));
    service.activate(PROP, OTHER_PLAN, revision(b));
    assertEquals(1, a.getActiveVersion());
    assertEquals(1, b.getActiveVersion());
  }

  @Test
  void refusesExpiredPlanActivationAndStaleRevision() {
    var a = create(PLAN);
    doThrow(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Expired Plan"))
        .when(access)
        .active(any());
    assertThrows(BadRequestException.class, () -> service.activate(PROP, PLAN, revision(a)));
    assertThrows(
        ResponseStatusException.class, () -> service.edit(PROP, PLAN, new PlanSaleOrchestratorDtos.Save(-1L, config())));
  }

  @Test
  void handlesConcurrentCreateAndVersionConflicts() {
    doThrow(new DuplicateKeyException("race")).when(repo).save(any());
    assertEquals(
        409,
        assertThrows(ResponseStatusException.class, () -> create(PLAN)).getStatusCode().value());
    doThrow(new OptimisticLockingFailureException("race")).when(repo).save(any());
    assertEquals(
        409,
        assertThrows(ResponseStatusException.class, () -> create(PLAN)).getStatusCode().value());
  }

  @Test
  void returnsNoConfigurationForPlanUntilCreated() {
    assertTrue(service.get(PROP, PLAN).isEmpty());
  }
}
