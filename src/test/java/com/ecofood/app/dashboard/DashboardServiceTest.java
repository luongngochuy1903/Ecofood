package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static com.ecofood.app.dashboard.DashboardDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class DashboardServiceTest {
  DashboardAccess access = mock(DashboardAccess.class); DashboardData data = mock(DashboardData.class); CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
  DashboardService service = new DashboardService(access, data, new DashboardAggregation(), tenants);
  @BeforeEach void setup() { when(access.current()).thenReturn(actor(false)); when(data.propositions(any())).thenReturn(List.of(proposition())); when(tenants.propositionReference(A)).thenReturn(tenant(A, false)); when(tenants.propositionReference(B)).thenReturn(tenant(B, false)); when(data.actual(anySet(), anySet(), any(), any())).thenReturn(List.of(actual(A, 1, "USD", 1, "2"))); }
  @Test void tenantExpectedUsesPartnerMetricsInsteadOfGlobalPropositionExpected() {
    var result = service.summary(selection(Period.QUARTERLY)); assertEquals(bd("30"), result.metrics().get("profit").expected()); assertEquals(List.of(A), result.tenantIds());
    verify(data).actual(Set.of(A), Set.of(PROP), 2026, "USD");
  }
  @Test void tenantCannotSelectAnotherTenantEvenWithKnownIdentifier() {
    assertThrows(ResponseStatusException.class, () -> service.summary(new Selection(B, PROP, "USD", 2026, Period.MONTHLY, 1, null, false)));
    verify(data, never()).actual(anySet(), anySet(), any(), any());
  }
  @Test void enterpriseTotalsSumTenantsOnceAndUseGlobalExpectedOnlyForFullScope() {
    when(access.current()).thenReturn(actor(true)); when(data.actual(anySet(), anySet(), any(), any())).thenReturn(List.of(actual(A, 1, "USD", 1, "2"), actual(B, 1, "USD", 2, "4")));
    var result = service.comparison(new Selection(null, PROP, "USD", 2026, Period.QUARTERLY, null, 1, true));
    assertEquals(bd("3"), result.summary().metrics().get("totalSalesVolume").actual()); assertEquals(bd("100"), result.summary().metrics().get("profit").expected());
    assertEquals(2, result.byTenant().size()); assertEquals(bd("30"), result.byTenant().get(0).summary().metrics().get("profit").expected()); assertEquals(bd("70"), result.byTenant().get(1).summary().metrics().get("profit").expected());
  }
  @Test void enterpriseWithPartialTenantScopeDoesNotExposeGlobalTarget() {
    var restricted = new DashboardAccess.Actor(USER, tenant(OWNER, true), Set.of(OWNER, A), true, null); when(access.current()).thenReturn(restricted);
    var result = service.summary(new Selection(null, PROP, "USD", 2026, Period.QUARTERLY, null, 1, true));
    assertEquals(List.of(A), result.tenantIds()); assertEquals(bd("30"), result.metrics().get("profit").expected());
  }
  @Test void quarterlyTrendsHaveFourPointsAndReuseOneActualRead() {
    var trends = service.trends(selection(Period.QUARTERLY)); assertEquals(4, trends.size()); assertEquals(1, trends.get(0).selection().quarter()); assertEquals(4, trends.get(3).selection().quarter());
    verify(data, times(1)).actual(anySet(), anySet(), eq(2026), eq("USD"));
  }
  @Test void inaccessiblePropositionAndInvalidCurrencyAreRejected() {
    when(data.propositions(any())).thenReturn(List.of()); assertThrows(ResponseStatusException.class, () -> service.summary(selection(Period.MONTHLY)));
    assertThrows(ResponseStatusException.class, () -> service.summary(new Selection(A, PROP, "NOT_CURRENCY", 2026, Period.MONTHLY, 1, null, false)));
  }
  @Test void legacyPropositionActualIsNotUsedAsSecondSource() {
    var prop = proposition(); prop.setActualMetrics(List.of(quarter(1, "999999"))); when(data.propositions(any())).thenReturn(List.of(prop));
    assertEquals(bd("2"), service.summary(selection(Period.MONTHLY)).metrics().get("profit").actual());
  }
  @Test void totalRequiresEnterprisePartnerPermissionAndOwnerAssociation() {
    assertThrows(ResponseStatusException.class, () -> service.summary(new Selection(null, PROP, "USD", 2026, Period.QUARTERLY, null, 1, true)));
    when(access.current()).thenReturn(new DashboardAccess.Actor(USER, tenant(OWNER, true), Set.of(OWNER), false, null));
    assertThrows(ResponseStatusException.class, () -> service.summary(new Selection(null, PROP, "USD", 2026, Period.QUARTERLY, null, 1, true)));
  }
  @Test void mismatchedExpectedCurrencyCannotBeHiddenByOtherMatchingTargets() {
    var changed = proposition(); var d = changed.getDefinition();
    changed.setDefinition(new com.ecofood.core.dto.PropositionDefinition(d.name(), d.description(), d.regionIds(), d.brandId(), d.distributionPartnerIds(), "VND", d.startDate(), d.endDate(), d.expectRevenue(), d.expectMetrics(), d.partnerMetrics(), d.enterpriseProfitShare(), d.platformProfitShare(), d.distributionProfitShare(), d.allowedOverrides()));
    var second = proposition(); second.setId(new org.bson.types.ObjectId("888888888888888888888888"));
    when(data.propositions(any())).thenReturn(List.of(changed, second));
    var result = service.summary(new Selection(A, null, "USD", 2026, Period.QUARTERLY, null, 1, false));
    assertNull(result.metrics().get("profit").expected()); assertEquals("CURRENCY_MISMATCH", result.metrics().get("profit").expectedStatus());
  }
  @Test void filterOptionsOnlyExposeTenantsAndPropositionAssignmentsInsideActorScope() {
    var result = service.filters(); assertEquals(List.of(A), result.tenants().stream().map(Option::id).toList());
    assertEquals(List.of(A), result.propositions().get(0).tenantIds()); assertFalse(result.partnerComparisonAllowed()); assertFalse(result.planFilterSupported());
  }
}
