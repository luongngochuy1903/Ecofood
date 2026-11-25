package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.dashboard.controller.DashboardController;
import com.ecofood.app.dashboard.exception.DashboardApiErrors;
import com.ecofood.app.dashboard.repository.DashboardData;
import com.ecofood.app.dashboard.security.DashboardAccess;
import com.ecofood.app.dashboard.service.DashboardServiceImpl;
import com.ecofood.app.metrics.entity.ActualMetrics;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.service.PropositionAccess;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DashboardApiTest {
  @Test void invalidFiltersAreRejectedAndUnsupportedPlanIsNotSilentlyIgnored() throws Exception {
    var service = mock(DashboardServiceImpl.class); var mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service)).setControllerAdvice(new DashboardApiErrors(), new GlobalExceptionHandler()).build();
    mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/dashboard/summary").param("currency", "USD").param("period", "NOT_A_PERIOD")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/dashboard/summary").param("currency", "USD").param("planId", "some-plan")).andExpect(status().isBadRequest()); verifyNoInteractions(service);
  }
  @Test void tenantHeaderDoesNotChooseDataScopeAndAuthorizationErrorsRemainForbidden() throws Exception {
    var service = mock(DashboardServiceImpl.class); when(service.summary(any())).thenThrow(DashboardAccess.denied());
    var mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service)).setControllerAdvice(new DashboardApiErrors(), new GlobalExceptionHandler()).build();
    mvc.perform(get("/api/v1/dashboard/summary").param("currency", "USD").header("X-Tenant-Id", B)).andExpect(status().isForbidden());
    verify(service).summary(argThat(s -> s.tenantId() == null));
  }
  @Test void projectionReadsPropositionActualsWithAllBusinessDimensionsAndNeverWrites() {
    var mongo = mock(MongoTemplate.class);
    var p = proposition();
    p.setActualMetrics(List.of(
        new com.ecofood.core.dto.EconomicMetricsRequest(1, 2026, bd("2"), bd("0"), bd("0"), bd("50"), null, bd("100"), A, "USD"),
        new com.ecofood.core.dto.EconomicMetricsRequest(1, 2026, bd("3"), bd("0"), bd("0"), bd("50"), null, bd("100"), B, "USD"),
        new com.ecofood.core.dto.EconomicMetricsRequest(1, 2026, bd("4"), bd("0"), bd("0"), bd("50"), null, bd("100"), A, "VND"),
        new com.ecofood.core.dto.EconomicMetricsRequest(1, 2025, bd("5"), bd("0"), bd("0"), bd("50"), null, bd("100"), A, "USD")));
    when(mongo.find(any(Query.class), eq(PropositionEntity.class))).thenReturn(List.of(p));
    var data = new DashboardData(mongo, mock(PropositionAccess.class));
    var rows = data.actual(Set.of(A), Set.of(PROP), 2026, "USD");
    assertEquals(1, rows.size());
    assertEquals(A, rows.get(0).tenantId());
    assertEquals("USD", rows.get(0).currency());
    assertEquals(2026, rows.get(0).year());
    assertEquals(1, rows.get(0).month());
    assertEquals(2, rows.get(0).totalSalesVolume());
    verify(mongo).find(argThat((Query q) -> q.getQueryObject().get("_id", org.bson.Document.class)
        .getList("$in", org.bson.types.ObjectId.class).equals(List.of(new org.bson.types.ObjectId(PROP)))
        && q.getQueryObject().get("deleted", org.bson.Document.class).getBoolean("$ne")), eq(PropositionEntity.class));
    verifyNoMoreInteractions(mongo);
  }
  @Test void productDarFiltersBeforeReturningPropositionsAndUsesExistingReadPolicy() {
    var mongo = mock(MongoTemplate.class); var policy = mock(PropositionAccess.class); when(mongo.find(any(Query.class), eq(PropositionEntity.class))).thenReturn(List.of(proposition()));
    var data = new DashboardData(mongo, policy); var restricted = new DashboardAccess.Actor(USER, tenant(A, false), Set.of(A), false, Set.of());
    assertTrue(data.propositions(restricted).isEmpty()); verifyNoInteractions(policy);
    when(policy.canRead(any(), any())).thenReturn(true); assertEquals(1, data.propositions(actor(false)).size());
  }
}
