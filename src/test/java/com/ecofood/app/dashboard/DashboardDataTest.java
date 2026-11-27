package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.dashboard.repository.DashboardData;
import com.ecofood.app.dashboard.service.DashboardAggregation;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.service.DashboardPropositionQueryServiceImpl;
import com.ecofood.core.client.CoreDashboardPropositionClient;
import com.ecofood.core.dto.DashboardActor;
import com.ecofood.core.dto.DashboardDtos.Selection;
import com.ecofood.core.dto.EconomicMetricsRequest;
import com.ecofood.core.enums.DashboardPeriodEnum;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class DashboardDataTest {
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final CoreDashboardPropositionClient client = mock(CoreDashboardPropositionClient.class);
  private final DashboardData data = new DashboardData(client);
  private final DashboardPropositionQueryServiceImpl query = new DashboardPropositionQueryServiceImpl(mongo);

  private EconomicMetricsRequest row(String tenant, String currency, int year) {
    return new EconomicMetricsRequest(11, year, bd("2"), bd("-5"), bd("0"), bd("50"),
        null, bd("100"), tenant, currency);
  }

  @Test
  void readsPropositionActualsThroughCoreAndSeparatesTenantCurrencyAndYear() {
    var p = proposition();
    p.setActualMetrics(List.of(row(A, "USD", 2026), row(A, "VND", 2026),
        row(B, "USD", 2026), row(A, "USD", 2025)));
    when(mongo.find(any(Query.class), eq(PropositionEntity.class))).thenReturn(List.of(p));
    when(client.find(A, Set.of(PROP))).thenAnswer(ignored -> query.find(A, Set.of(PROP)));
    var actor = new DashboardActor(USER, tenant(A, false), Set.of(A), false, Set.of(PROP));
    var propositions = data.propositions(actor);
    assertTrue(propositions.get(0).actualMetrics().stream().allMatch(r -> A.equals(r.tenantId())));

    var aggregation = new DashboardAggregation();
    var summary = aggregation.embedded(new Selection(A, PROP, "USD", 2026,
        DashboardPeriodEnum.YEARLY, null, null, false), A, propositions);
    assertEquals(bd("2"), summary.metrics().get("totalSalesVolume").actual());
    assertEquals(bd("-5"), summary.metrics().get("profit").actual());
    assertEquals("USD", summary.metrics().get("profit").unit());
    var absent = aggregation.embedded(new Selection(A, PROP, "USD", 2027,
        DashboardPeriodEnum.YEARLY, null, null, false), A, propositions);
    assertNull(absent.metrics().get("profit").actual());
    verify(client).find(A, Set.of(PROP));
    verify(mongo).find(any(Query.class), eq(PropositionEntity.class));
    verifyNoMoreInteractions(mongo);
  }

  @Test
  void emptyScopeDoesNotReadAnyCollection() {
    var actor = new DashboardActor(USER, tenant(A, false), Set.of(), false, Set.of());
    assertTrue(data.propositions(actor).isEmpty());
    assertTrue(query.find(A, Set.of()).isEmpty());
    verifyNoInteractions(client, mongo);
  }
}
