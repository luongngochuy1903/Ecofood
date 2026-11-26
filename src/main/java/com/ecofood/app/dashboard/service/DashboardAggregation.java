package com.ecofood.app.dashboard.service;
import static com.ecofood.core.dto.DashboardDtos.*;
import com.ecofood.core.dto.EconomicMetricsRequest;
import com.ecofood.core.enums.DashboardPeriodEnum;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;
/** Sums stored Proposition actual metrics; never recalculates transaction economics. */
@Component
public class DashboardAggregation {
  public Summary embedded(Selection selection, String tenant, List<com.ecofood.core.dto.DashboardProposition> propositions) {
    List<EconomicMetricsRequest> rows = propositions.stream().filter(p -> p.actualMetrics() != null)
        .flatMap(p -> p.actualMetrics().stream()).filter(r -> tenant.equals(r.tenantId())
            && Objects.equals(r.year(), selection.year()) && selection.currency().equals(r.currency())
            && (selection.period() == DashboardPeriodEnum.YEARLY
                || Objects.equals(r.quarter(), selection.quarter())
                || r.month() != null && selection.months().contains(r.month()))).toList();
    Map<String, Metric> metrics = new LinkedHashMap<>();
    Map<String, java.util.function.Function<EconomicMetricsRequest, BigDecimal>> fields = new LinkedHashMap<>();
    fields.put("revenue", EconomicMetricsRequest::revenue);
    fields.put("totalSalesVolume", EconomicMetricsRequest::totalSalesVolume);
    fields.put("profit", EconomicMetricsRequest::profit);
    fields.put("platformCost", EconomicMetricsRequest::platformCost);
    fields.forEach((name, getter) -> {
      boolean complete = !rows.isEmpty() && rows.stream().allMatch(r -> getter.apply(r) != null);
      BigDecimal actual = complete ? rows.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
      metrics.put(name, new Metric(actual, null, null, null, null, rows.isEmpty() ? "NO_RECORDED_TRANSACTIONS" : complete ? "COMPLETE" : "INCOMPLETE",
          "NOT_CONFIGURED", name.equals("totalSalesVolume") ? "PLAN" : selection.currency(), "ACTUAL", 0, 0));
    });
    return new Summary(selection, List.of(tenant), metrics, null, "Asia/Ho_Chi_Minh", List.of());
  }
}
