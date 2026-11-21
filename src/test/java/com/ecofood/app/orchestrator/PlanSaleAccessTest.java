package com.ecofood.app.orchestrator;

import static com.ecofood.app.orchestrator.SaleFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.app.proposition.service.PropositionAccess;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class PlanSaleAccessTest {
  private final PropositionAccess props = mock(PropositionAccess.class);
  private final PropositionRepository repo = mock(PropositionRepository.class);
  private final PlanSaleAccess access = new PlanSaleAccess(props, repo);
  private PropositionEntity p;
  private Plan plan;

  @BeforeEach
  void setup() {
    var d = mock(PropositionDefinition.class);
    when(d.distributionPartnerIds()).thenReturn(List.of(TENANT));
    when(d.currency()).thenReturn("VND");
    plan = Plan.builder().tenantIds(List.of(TENANT)).status(PlanStatusEnum.APPROVED).build();
    plan.setId(new ObjectId(PLAN));
    p =
        PropositionEntity.builder()
            .definition(d)
            .status(PropositionStatusEnum.APPROVED)
            .plans(List.of(plan))
            .build();
    p.setId(new ObjectId(PROP));
    when(repo.findById(new ObjectId(PROP))).thenReturn(Optional.of(p));
  }

  @Test
  void requiresPlanInCorrectPropositionAndExactTenant() {
    assertSame(plan, access.require(TENANT, PROP, PLAN).plan());
    assertThrows(ResponseStatusException.class, () -> access.require("other", PROP, PLAN));
    assertThrows(ResponseStatusException.class, () -> access.require(TENANT, PROP, OTHER_PLAN));
    assertThrows(ResponseStatusException.class, () -> access.require(TENANT, "invalid", PLAN));
  }

  @Test
  void separatesDraftConfigurationAccessFromActivationAndChecksLiveDates() {
    plan.setStatus(PlanStatusEnum.DRAFT);
    var scope = access.require(TENANT, PROP, PLAN);
    assertThrows(ResponseStatusException.class, () -> access.active(scope));
    plan.setStatus(PlanStatusEnum.APPROVED);
    access.active(scope);
    plan.setEndDate(Instant.now().minusSeconds(1));
    assertThrows(ResponseStatusException.class, () -> access.active(scope));
    plan.setEndDate(null);
    p.setDeleted(true);
    assertThrows(ResponseStatusException.class, () -> access.require(TENANT, PROP, PLAN));
  }

  @Test
  void onlyTenantAdminAndProductManagerCanManageTheirOwnAssignedPlan() {
    var tenant = mock(PropositionTenantReference.class);
    when(tenant.id()).thenReturn(TENANT);
    when(props.current())
        .thenReturn(
            new PropositionAccess.Actor(
                tenant, Set.of(TENANT, "child"), Set.of(TENANT), false, false));
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_ADMIN"));
      auth.when(AuthenticationContext::getClaims)
          .thenReturn(Jwts.claims().subject("employee").build());
      assertEquals(TENANT, access.current(PROP, PLAN).tenantId());
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_PRODUCT_MANAGER"));
      assertEquals("employee", access.current(PROP, PLAN).actor());
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_PARTNERSHIP_MANAGER"));
      assertThrows(ForbiddenException.class, () -> access.current(PROP, PLAN));
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("ENTERPRISE_ADMIN"));
      assertThrows(ForbiddenException.class, () -> access.current(PROP, PLAN));
    }
  }

  @Test
  void changingOnePlanDoesNotChangeOtherPlanAvailability() {
    var other = Plan.builder().tenantIds(List.of(TENANT)).status(PlanStatusEnum.APPROVED).build();
    other.setId(new ObjectId(OTHER_PLAN));
    p.setPlans(List.of(plan, other));
    plan.setEndDate(Instant.now().minusSeconds(1));
    access.active(access.require(TENANT, PROP, OTHER_PLAN));
  }
}
