package com.ecofood.app.orchestrator;

import static com.ecofood.app.orchestrator.SaleFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.app.proposition.service.PropositionAccess;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.dto.PropositionDefinition;
import com.ecofood.core.dto.PropositionTenantReference;
import com.ecofood.core.enums.*;
import com.ecofood.core.enums.PlanStatusEnum;
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
  private final CoreProductProcessClient productProcesses = mock(CoreProductProcessClient.class);
  private final PlanSaleAccess access = new PlanSaleAccess(props, repo, productProcesses);
  private PropositionEntity p;
  private Plan plan;

  @BeforeEach
  void setup() {
    var d = mock(PropositionDefinition.class);
    when(d.distributionPartnerIds()).thenReturn(List.of(TENANT));
    when(d.currency()).thenReturn("VND");
    plan =
        Plan.builder()
            .tenantIds(List.of(TENANT))
            .status(PlanStatusEnum.APPROVED)
            .productVersion(1)
            .build();
    plan.setId(new ObjectId(PLAN));
    p =
        PropositionEntity.builder()
            .definition(d)
            .plans(List.of(plan))
            .productId(new ObjectId(PRODUCT))
            .build();
    p.setId(new ObjectId(PROP));
    when(repo.findById(new ObjectId(PROP))).thenReturn(Optional.of(p));
  }

  @Test
  void requiresPlanInCorrectPropositionAndDistributedTenant() {
    assertSame(plan, access.require(TENANT, PROP, PLAN).plan());
    assertThrows(ResponseStatusException.class, () -> access.require("other", PROP, PLAN));
    assertThrows(ResponseStatusException.class, () -> access.require(TENANT, PROP, OTHER_PLAN));
    assertThrows(ResponseStatusException.class, () -> access.require(TENANT, "invalid", PLAN));
  }

  @Test
  void sharesOnlyTheOwningEnterprisesConfigurationWithPropositionDistributors() {
    p.setEnterpriseId(new ObjectId(PRODUCT));
    p.setOwningTenantId(new ObjectId(OTHER_PLAN));
    assertTrue(access.canUseEnterpriseConfiguration(TENANT, PROP, PLAN, OTHER_PLAN));
    assertFalse(access.canUseEnterpriseConfiguration(TENANT, PROP, PLAN, PRODUCT));
    assertFalse(access.canUseEnterpriseConfiguration("other-tenant", PROP, PLAN, OTHER_PLAN));
    assertFalse(access.canUseEnterpriseConfiguration(TENANT, PROP, OTHER_PLAN, OTHER_PLAN));

    plan.setTenantIds(List.of());
    assertTrue(access.canUseEnterpriseConfiguration(TENANT, PROP, PLAN, OTHER_PLAN));
    assertSame(plan, access.require(TENANT, PROP, PLAN).plan());
    plan.setTenantIds(List.of(TENANT));
    plan.setDeleted(true);
    assertFalse(access.canUseEnterpriseConfiguration(TENANT, PROP, PLAN, OTHER_PLAN));
    plan.setDeleted(false);
    when(p.getDefinition().distributionPartnerIds()).thenReturn(List.of());
    assertFalse(access.canUseEnterpriseConfiguration(TENANT, PROP, PLAN, OTHER_PLAN));
    when(p.getDefinition().distributionPartnerIds()).thenReturn(List.of(TENANT));
    p.setDeleted(true);
    assertFalse(access.canUseEnterpriseConfiguration(TENANT, PROP, PLAN, OTHER_PLAN));
    p.setDeleted(false);
    p.setEnterpriseId(null);
    assertFalse(access.canUseEnterpriseConfiguration(TENANT, PROP, PLAN, OTHER_PLAN));
    assertFalse(access.canUseEnterpriseConfiguration(TENANT, "invalid", PLAN, OTHER_PLAN));
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
  void onlyTenantAdminAndProductManagerCanManageTheirOwnTenant() {
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
      assertEquals(TENANT, access.currentForProposition(PROP).tenantId());
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_PRODUCT_MANAGER"));
      assertEquals("employee", access.currentForProposition(PROP).actor());
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_PARTNERSHIP_MANAGER"));
      assertThrows(ForbiddenException.class, () -> access.currentForProposition(PROP));
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("ENTERPRISE_ADMIN"));
      assertThrows(ForbiddenException.class, () -> access.currentForProposition(PROP));
    }
  }

  @Test
  void tenantDeclaredAsAPropositionLevelDistributionPartnerSeesEveryPlanEvenOnesNotListingItDirectly() {
    // other.tenantIds deliberately does NOT include TENANT — only the
    // Proposition's own distributionPartnerIds (set in @BeforeEach) does.
    var other = Plan.builder().tenantIds(List.of("someone-else")).status(PlanStatusEnum.APPROVED).build();
    other.setId(new ObjectId(OTHER_PLAN));
    p.setPlans(List.of(plan, other));
    var tenant = mock(PropositionTenantReference.class);
    when(tenant.id()).thenReturn(TENANT);
    when(props.current())
        .thenReturn(new PropositionAccess.Actor(tenant, Set.of(TENANT), Set.of(TENANT), false, false));
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_ADMIN"));
      auth.when(AuthenticationContext::getClaims)
          .thenReturn(Jwts.claims().subject("employee").build());
      assertEquals(2, access.currentForProposition(PROP).plans().size());
    }
  }

  @Test
  void requireSucceedsForADistributionPartnerEvenWhenThePlanDoesNotListItDirectly() {
    plan.setTenantIds(List.of("someone-else"));
    assertSame(plan, access.require(TENANT, PROP, PLAN).plan());
  }

  @Test
  void enterpriseActorsWithAdminOrProductManagerRoleSeeEveryPlanInTheirOwnProposition() {
    var other = Plan.builder().tenantIds(List.of("someone-else")).status(PlanStatusEnum.APPROVED).build();
    other.setId(new ObjectId(OTHER_PLAN));
    p.setPlans(List.of(plan, other));
    var enterpriseTenant = mock(PropositionTenantReference.class);
    when(enterpriseTenant.id()).thenReturn("enterprise-tenant");
    when(props.current())
        .thenReturn(new PropositionAccess.Actor(enterpriseTenant, Set.of(), Set.of(), true, false));
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getClaims)
          .thenReturn(Jwts.claims().subject("employee").build());
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("ENTERPRISE_ADMIN"));
      var scope = access.currentForProposition(PROP);
      assertEquals("enterprise-tenant", scope.tenantId());
      assertEquals(2, scope.plans().size());
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("ENTERPRISE_PRODUCT_MANAGER"));
      assertEquals(2, access.currentForProposition(PROP).plans().size());
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_ADMIN"));
      assertThrows(ForbiddenException.class, () -> access.currentForProposition(PROP));
    }
  }

  @Test
  void platformActorsCanNeverManageSaleOrchestratorsRegardlessOfRole() {
    var platformTenant = mock(PropositionTenantReference.class);
    when(platformTenant.id()).thenReturn("eco-tenant");
    when(props.current())
        .thenReturn(new PropositionAccess.Actor(platformTenant, Set.of(), Set.of(), false, true));
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getRoles).thenReturn(List.of("ECO_ADMIN"));
      assertThrows(ForbiddenException.class, () -> access.currentForProposition(PROP));
    }
  }

  @Test
  void changingOnePlanDoesNotChangeOtherPlanAvailability() {
    var other =
        Plan.builder()
            .tenantIds(List.of(TENANT))
            .status(PlanStatusEnum.APPROVED)
            .productVersion(1)
            .build();
    other.setId(new ObjectId(OTHER_PLAN));
    p.setPlans(List.of(plan, other));
    plan.setEndDate(Instant.now().minusSeconds(1));
    access.active(access.require(TENANT, PROP, OTHER_PLAN));
  }

  @Test
  void activeSaleUsesPlanApprovalWithoutRequiringProductLibraryAccess() {
    var scope = access.require(TENANT, PROP, PLAN);
    assertDoesNotThrow(() -> access.active(scope));
    verifyNoInteractions(productProcesses, props);
  }

  @Test
  void activeSaleDoesNotRequireSourceProductVersionMetadata() {
    plan.setProductVersion(null);
    var scope = access.require(TENANT, PROP, PLAN);
    assertDoesNotThrow(() -> access.active(scope));
  }
}
