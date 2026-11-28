package com.ecofood.app.dashboard;
import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ecofood.app.dashboard.repository.DashboardData;
import com.ecofood.app.dashboard.security.DashboardAccess;
import com.ecofood.app.dashboard.service.*;
import com.ecofood.core.client.CoreDashboardPropositionClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class DashboardServiceTest {
  @Test void selectedParentExpandsAuthorizedChildrenAndOverlappingSelectionReadsEachTenantOnce() {
    var access = mock(DashboardAccess.class); var data = mock(DashboardData.class);
    var b = new DashboardActor(USER,tenant(B,false),Set.of(B),false,null);
    var c = new DashboardActor(USER,tenant(OWNER,false),Set.of(OWNER),false,null);
    when(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)).thenReturn(List.of(b,c));
    when(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,B)).thenReturn(List.of(b));
    var legacy = new EconomicMetricsRequest(null,2026,bd("10"),bd("10"),null,bd("10"),4,bd("10"),null,"USD");
    when(data.propositions(b)).thenReturn(List.of(new DashboardProposition(PROP,"Shared","USD",List.of(legacy))));
    when(data.propositions(c)).thenReturn(List.of(new DashboardProposition(PROP,"Shared","USD",List.of(legacy))));
    var selection = new DashboardDtos.Selection(null,null,"USD",2026,DashboardPeriodEnum.QUARTERLY,null,4,false,List.of(A,B));
    var result = new DashboardServiceImpl(access,data,new DashboardAggregation()).summary(selection);
    assertEquals(Set.of(B,OWNER),new HashSet<>(result.tenantIds()));
    assertEquals(bd("10"),result.metrics().get("revenue").actual());
    verify(data,times(1)).propositions(b); verify(data,times(1)).propositions(c); verifyNoMoreInteractions(data);
  }
  @Test void missingRuleDoesNotReadPropositions() {
    var client = mock(CoreDashboardPropositionClient.class);
    var empty = new com.ecofood.core.dto.DashboardActor(USER, tenant(A,false),Set.of(),false,Set.of());
    assertTrue(new DashboardData(client).propositions(empty).isEmpty());
    verifyNoInteractions(client);
  }
  @Test void limitedIdsAreForwardedToOwningModule() {
    var client = mock(CoreDashboardPropositionClient.class);
    var actor = new com.ecofood.core.dto.DashboardActor(USER,tenant(A,false),Set.of(A),false,Set.of(PROP));
    when(client.find(A,Set.of(PROP))).thenReturn(List.of());
    new DashboardData(client).propositions(actor);
    verify(client).find(A,Set.of(PROP));
  }
  @Test void childTenantSummaryAndTrendUseTheSameEmbeddedActuals() {
    var access = mock(DashboardAccess.class); var data = mock(DashboardData.class);
    var child = new DashboardActor(USER,tenant(B,false),Set.of(B),false,null);
    when(access.current()).thenReturn(actor(false)); // Session Tenant A differs from selected Tenant B.
    when(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,B)).thenReturn(List.of(child));
    var stored = new EconomicMetricsRequest(11,2026,bd("5"),bd("30"),null,bd("10"),null,bd("250"),B,"USD");
    when(data.propositions(child)).thenReturn(List.of(new DashboardProposition(PROP,"Proposition","USD",List.of(stored))));
    var service = new DashboardServiceImpl(access,data,new DashboardAggregation());
    var selection = new DashboardDtos.Selection(B,PROP,"USD",2026,DashboardPeriodEnum.QUARTERLY,null,4,false);
    var summary = service.summary(selection); var trend = service.trends(selection);
    assertEquals(bd("250"),summary.metrics().get("revenue").actual());
    assertEquals(bd("250"),trend.get(3).metrics().get("revenue").actual());
    assertEquals(bd("5"),trend.get(3).metrics().get("totalSalesVolume").actual());
    assertEquals(bd("30"),trend.get(3).metrics().get("profit").actual());
    assertEquals(bd("10"),trend.get(3).metrics().get("platformCost").actual());
    assertEquals(List.of(B),trend.get(3).tenantIds());
    assertNull(trend.get(0).metrics().get("revenue").actual());
    var yearly = new DashboardDtos.Selection(B,PROP,"USD",2026,DashboardPeriodEnum.YEARLY,null,null,false);
    assertEquals(service.summary(yearly).metrics().get("revenue").actual(),service.trends(yearly).get(0).metrics().get("revenue").actual());
  }
  @Test void multipleTenantsSumDistinctActualRowsAndCountSharedLegacyTotalsOnce() {
    var access = mock(DashboardAccess.class); var data = mock(DashboardData.class);
    var b = new DashboardActor(USER,tenant(B,false),Set.of(B),false,null);
    var c = new DashboardActor(USER,tenant(OWNER,false),Set.of(OWNER),false,null);
    when(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,B)).thenReturn(List.of(b));
    when(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,OWNER)).thenReturn(List.of(c));
    var legacy = new EconomicMetricsRequest(null,2026,bd("10"),bd("10"),null,bd("10"),4,bd("10"),null,"USD");
    var rowB = new EconomicMetricsRequest(11,2026,bd("20"),bd("20"),null,bd("20"),null,bd("20"),B,"USD");
    var rowC = new EconomicMetricsRequest(11,2026,bd("30"),bd("30"),null,bd("30"),null,bd("30"),OWNER,"USD");
    when(data.propositions(b)).thenReturn(List.of(new DashboardProposition(PROP,"Shared proposition","USD",List.of(legacy,rowB))));
    when(data.propositions(c)).thenReturn(List.of(new DashboardProposition(PROP,"Shared proposition","USD",List.of(legacy,rowC))));
    var service = new DashboardServiceImpl(access,data,new DashboardAggregation());
    var selection = new DashboardDtos.Selection(null,PROP,"USD",2026,DashboardPeriodEnum.QUARTERLY,null,4,false,List.of(B,OWNER,B));
    assertEquals(2,selection.tenantIds().size());
    var summary = service.summary(selection);
    assertEquals(bd("60"),summary.metrics().get("revenue").actual());
    assertEquals(Set.of(B,OWNER),new HashSet<>(summary.tenantIds()));
    assertEquals(bd("60"),service.trends(selection).get(3).metrics().get("revenue").actual());
  }
  @Test void selectingTenantWithoutRuleDeniesTheWholeRequestBeforeAnyDataRead() {
    var access = mock(DashboardAccess.class); var data = mock(DashboardData.class);
    when(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,B)).thenReturn(List.of(new DashboardActor(USER,tenant(B,false),Set.of(B),false,null)));
    when(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,OWNER)).thenReturn(List.of());
    var service = new DashboardServiceImpl(access,data,new DashboardAggregation());
    var s = new DashboardDtos.Selection(null,null,"USD",2026,DashboardPeriodEnum.QUARTERLY,null,4,false,List.of(B,OWNER));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.summary(s));
    verifyNoInteractions(data);
  }
  @Test void emptyOrAmbiguousMultiTenantSelectionIsRejected() {
    var data = mock(DashboardData.class); var access = mock(DashboardAccess.class);
    var service = new DashboardServiceImpl(access,data,new DashboardAggregation());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.summary(new DashboardDtos.Selection(null,null,"USD",2026,DashboardPeriodEnum.QUARTERLY,null,4,false,List.of())));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.summary(new DashboardDtos.Selection(A,null,"USD",2026,DashboardPeriodEnum.QUARTERLY,null,4,false,List.of(B))));
    verifyNoInteractions(data,access);
  }
  @Test void filtersIncludeUnavailableChildNamesWithoutReadingTheirData() {
    var access = mock(DashboardAccess.class); var data = mock(DashboardData.class);
    var parent = new DashboardActor(USER,tenant(A,true),Set.of(),false,Set.of());
    var child = new DashboardActor(USER,tenant(B,false),Set.of(B),false,null);
    var unavailable = new DashboardActor(USER,tenant(OWNER,false),Set.of(),false,Set.of());
    when(access.current()).thenReturn(parent);
    when(access.tenantOptions(AccessEntityTypeEnum.BUSINESS_DASHBOARD)).thenReturn(List.of(parent,child,unavailable));
    when(access.selectableTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD)).thenReturn(List.of(new DashboardDtos.Option(A,"Parent",true,List.of(B)),new DashboardDtos.Option(B,"Child"),new DashboardDtos.Option(OWNER,"Unavailable",false)));
    when(data.propositions(child)).thenReturn(List.of(new DashboardProposition(PROP,"Prop","USD",List.of())));
    var result = new DashboardServiceImpl(access,data,new DashboardAggregation()).filters();
    assertEquals(3,result.tenants().size());
    assertTrue(result.tenants().get(0).accessible()); assertTrue(result.tenants().get(1).accessible());
    assertFalse(result.tenants().get(2).accessible());
    assertEquals(List.of(B),result.propositions().get(0).tenantIds());
    verify(data).propositions(child); verifyNoMoreInteractions(data);
  }
  @Test void monthlyIsRejected() {
    var access = mock(DashboardAccess.class);
    when(access.current(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)).thenReturn(actor(false));
    var service = new DashboardServiceImpl(access,mock(DashboardData.class),new DashboardAggregation());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.summary(selection(DashboardPeriodEnum.MONTHLY)));
  }
}
