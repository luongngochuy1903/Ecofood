package com.ecofood.app.dashboard;
import static org.junit.jupiter.api.Assertions.*;
import com.ecofood.app.dashboard.service.DashboardAggregation;
import com.ecofood.core.dto.*;
import com.ecofood.core.dto.DashboardDtos.Selection;
import com.ecofood.core.enums.DashboardPeriodEnum;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
class DashboardAggregationTest {
  EconomicMetricsRequest row(int quarter, String tenant, String currency, String value) {
    var amount = new BigDecimal(value);
    return new EconomicMetricsRequest(null, 2026, amount, amount, null, amount, quarter, amount, tenant, currency);
  }
  @Test void sumsStoredActualsByTenantCurrencyQuarterAndYear() {
    var props = List.of(new DashboardProposition("p", "P", "USD", List.of(row(1,"a","USD","10"), row(2,"a","USD","20"), row(1,"b","USD","999"), row(1,"a","VND","999"))));
    var aggregation = new DashboardAggregation();
    var quarter = aggregation.embedded(new Selection("a",null,"USD",2026,DashboardPeriodEnum.QUARTERLY,null,1,false), "a", props);
    assertEquals(new BigDecimal("10"), quarter.metrics().get("revenue").actual());
    assertEquals(Set.of("revenue","totalSalesVolume","profit","platformCost"),quarter.metrics().keySet());
    var year = aggregation.embedded(new Selection("a",null,"USD",2026,DashboardPeriodEnum.YEARLY,null,null,false), "a", props);
    assertEquals(new BigDecimal("30"), year.metrics().get("revenue").actual());
  }
  @Test void absentRowsDoNotInventZeroActuals() {
    var result = new DashboardAggregation().embedded(new Selection("a",null,"USD",2026,DashboardPeriodEnum.YEARLY,null,null,false),"a",List.of());
    assertNull(result.metrics().get("revenue").actual());
  }
}
