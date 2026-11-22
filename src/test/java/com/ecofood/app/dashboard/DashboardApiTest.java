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
  @Test void projectionReadsActualMetricsClassWithAllBusinessDimensionsAndNeverWrites() {
    var mongo = mock(MongoTemplate.class); when(mongo.find(any(Query.class), eq(ActualMetrics.class))).thenReturn(List.of(actual(A, 1, "USD", 1, "0")));
    var data = new DashboardData(mongo, mock(PropositionAccess.class)); assertEquals(1, data.actual(Set.of(A), Set.of(PROP), 2026, "USD").size());
    verify(mongo).find(argThat((Query q) -> q.getQueryObject().containsKey("tenantId") && q.getQueryObject().containsKey("propositionId") && q.getQueryObject().getInteger("year") == 2026 && "USD".equals(q.getQueryObject().getString("currency"))), eq(ActualMetrics.class));
    verifyNoMoreInteractions(mongo);
  }
  @Test void productDarFiltersBeforeReturningPropositionsAndUsesExistingReadPolicy() {
    var mongo = mock(MongoTemplate.class); var policy = mock(PropositionAccess.class); when(mongo.find(any(Query.class), eq(PropositionEntity.class))).thenReturn(List.of(proposition()));
    var data = new DashboardData(mongo, policy); var restricted = new DashboardAccess.Actor(USER, tenant(A, false), Set.of(A), false, Set.of());
    assertTrue(data.propositions(restricted).isEmpty()); verifyNoInteractions(policy);
    when(policy.canRead(any(), any())).thenReturn(true); assertEquals(1, data.propositions(actor(false)).size());
  }
}
