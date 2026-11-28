package com.ecofood.app.dashboard;
import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.service.DashboardPropositionQueryServiceImpl;
import com.ecofood.core.dto.EconomicMetricsRequest;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
class DashboardSourceTest {
  @Test void readsEmbeddedPropositionMetricsAndExcludesAnotherTenantsRows() {
    var mongo = mock(MongoTemplate.class);
    var p = proposition();
    p.setActualMetrics(List.of(new EconomicMetricsRequest(null,2026,bd("10"),bd("4"),bd("3"),bd("2"),1,bd("100")),
        new EconomicMetricsRequest(null,2026,bd("999"),bd("999"),null,null,1,bd("999"),B,"USD")));
    when(mongo.find(any(Query.class),eq(PropositionEntity.class))).thenReturn(List.of(p));
    var result = new DashboardPropositionQueryServiceImpl(mongo).find(A,Set.of(PROP));
    assertEquals(1,result.get(0).actualMetrics().size());
    assertEquals(bd("100"),result.get(0).actualMetrics().get(0).revenue());
    assertEquals("USD",result.get(0).actualMetrics().get(0).currency());
    verify(mongo).find(argThat((Query q) -> q.getQueryObject().containsKey("_id")),eq(PropositionEntity.class));
    verifyNoMoreInteractions(mongo);
  }
}
