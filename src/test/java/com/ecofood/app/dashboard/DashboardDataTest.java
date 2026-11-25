package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.dashboard.repository.DashboardData;
import com.ecofood.app.metrics.entity.ActualMetrics;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.service.PropositionAccess;
import com.ecofood.core.dto.EconomicMetricsRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class DashboardDataTest {
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final DashboardData data = new DashboardData(mongo, mock(PropositionAccess.class));

  private EconomicMetricsRequest row(String tenant, String currency, int month) {
    return new EconomicMetricsRequest(month, 2026, bd("2"), bd("-5"), bd("0"), bd("50"),
        null, bd("100"), tenant, currency);
  }

  @Test
  void readsPropositionActualsAndSeparatesTenantCurrencyAndYear() {
    var p = proposition();
    p.setActualMetrics(List.of(row(A, "USD", 11), row(A, "VND", 11), row(B, "USD", 11), quarter(4, "9999")));
    when(mongo.find(any(Query.class), eq(PropositionEntity.class))).thenReturn(List.of(p));
    var rows = data.actual(Set.of(A), Set.of(PROP), 2026, "USD");
    assertEquals(1, rows.size());
    assertEquals(2, rows.get(0).totalSalesVolume());
    assertEquals(bd("-5"), rows.get(0).profit());
    assertEquals("USD", rows.get(0).currency());
    assertTrue(data.actual(Set.of(A), Set.of(PROP), 2027, "USD").isEmpty());
    verify(mongo, never()).find(any(Query.class), eq(ActualMetrics.class));
  }

  @Test
  void emptyScopeDoesNotReadAnyCollection() {
    assertTrue(data.actual(Set.of(), Set.of(PROP), null, null).isEmpty());
    assertTrue(data.actual(Set.of(A), Set.of(), null, null).isEmpty());
    verifyNoInteractions(mongo);
  }
}
