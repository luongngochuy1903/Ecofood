package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardDtos.*;

import com.ecofood.app.metrics.entity.ActualMetrics;
import com.ecofood.core.dto.EconomicMetricsRequest;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Aggregates stored KPI values only; this is not another financial calculator. */
@Component
public class DashboardAggregation {
  private static final String PROFIT = "profit";
  private static final String TOTAL_SALES_VOLUME = "totalSalesVolume";
  private static final String INGREDIENT_COST = "ingredientCost";
  private static final String PLATFORM_COST = "platformCost";
  public static final List<String> KPIS = List.of(TOTAL_SALES_VOLUME, PROFIT, INGREDIENT_COST, PLATFORM_COST);
  public record Target(String currency, List<EconomicMetricsRequest> rows) {}
  private record Value(BigDecimal amount, String status) {}
  public Summary summarize(Selection selection, Set<String> tenants, List<Target> targets, List<ActualMetrics> input) {
    var rows = input.stream().filter(r -> tenants.contains(r.tenantId()) && r.year() == selection.year()
        && selection.currency().equals(r.currency()) && selection.months().contains(r.month())
        && (selection.propositionId() == null || selection.propositionId().equals(r.propositionId()))).toList();
    Set<String> buckets = new HashSet<>(); long volume = 0;
    for (var row : rows) {
      String key = row.propositionId() + "/" + row.tenantId() + "/" + row.year() + "/" + row.month() + "/" + row.currency();
      if (!buckets.add(key) || row.totalSalesVolume() < 0) throw DashboardAccess.error(HttpStatus.CONFLICT, "Inconsistent ActualMetrics buckets");
      volume = Math.addExact(volume, row.totalSalesVolume());
    }
    Map<String, Metric> metrics = new LinkedHashMap<>();
    for (String name : KPIS) {
      boolean count = name.equals(TOTAL_SALES_VOLUME);
      boolean cost = name.endsWith("Cost");
      BigDecimal actual = count ? BigDecimal.valueOf(volume) : BigDecimal.ZERO;
      long covered = count ? volume : 0; boolean complete = true;
      for (var row : rows) if (!count) {
        BigDecimal value = actual(row, name); long coverage = coverage(row, name);
        if (coverage >= 0 && coverage <= row.totalSalesVolume()) covered = Math.addExact(covered, coverage);
        if (value == null || coverage != row.totalSalesVolume()) complete = false;
        else actual = actual.add(value);
      }
      String actualStatus = volume == 0 ? "NO_RECORDED_TRANSACTIONS" : complete ? "COMPLETE" : "INCOMPLETE";
      if (!complete || (!count && volume == 0)) actual = null;
      Value expected = expected(targets, selection, name);
      BigDecimal variance = actual != null && expected.amount() != null ? actual.subtract(expected.amount()) : null;
      BigDecimal achievement = !cost && expected.amount() != null && expected.amount().signum() > 0 && actual != null ? percent(actual, expected.amount()) : null;
      BigDecimal variancePercent = variance != null && expected.amount().signum() != 0 ? percent(variance, expected.amount().abs()) : null;
      metrics.put(name, new Metric(actual, expected.amount(), variance, achievement, variancePercent,
          actualStatus, expected.status(), count ? "PLAN" : selection.currency(), cost ? "BUDGET_VARIANCE" : "TARGET_ACHIEVEMENT", covered, volume));
    }
    Instant latest = rows.stream().map(ActualMetrics::updatedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    return new Summary(selection, tenants.stream().sorted().toList(), metrics, latest, "Asia/Ho_Chi_Minh",
        List.of("ActualMetrics is asynchronous; no recorded aggregate does not prove that no Invoice is pending.",
                "Expected is read from current Proposition configuration; no Expected configuration history is available."));
  }
  private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) { return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP); }
  private Value expected(List<Target> targets, Selection selection, String name) {
    if (targets.isEmpty()) return new Value(null, "NOT_CONFIGURED");
    BigDecimal total = BigDecimal.ZERO; boolean missing = false;
    for (var target : targets) {
      var value = expectedTarget(target, selection, name);
      if (value.status().equals("CURRENCY_UNVERIFIED") || value.status().equals("CURRENCY_MISMATCH")) return value;
      if (value.status().equals("OVERLAPPING_PERIODS") || value.status().equals("INVALID_PERIOD")) return value;
      if (value.amount() == null) missing = true; else total = total.add(value.amount());
    }
    return missing ? new Value(null, "NOT_FULLY_CONFIGURED") : new Value(total, "CONFIGURED");
  }
  private Value expectedTarget(Target target, Selection selection, String name) {
    if (target.currency() == null || target.currency().isBlank()) return new Value(null, "CURRENCY_UNVERIFIED");
    if (!selection.currency().equals(target.currency())) return new Value(null, "CURRENCY_MISMATCH");
    return expectedPeriod(target.rows() == null ? List.of() : target.rows(), selection, name);
  }
  private Value expectedPeriod(List<EconomicMetricsRequest> input, Selection selection, String name) {
    var rows = input.stream().filter(r -> r != null && Objects.equals(r.year(), selection.year())).toList();
    if (rows.stream().anyMatch(r -> (r.month() == null) == (r.quarter() == null)
        || (r.month() != null && (r.month() < 1 || r.month() > 12)) || (r.quarter() != null && (r.quarter() < 1 || r.quarter() > 4)))) return new Value(null, "INVALID_PERIOD");
    if (selection.period() == Period.MONTHLY) {
      var matching = rows.stream().filter(r -> Objects.equals(r.month(), selection.month())).toList();
      if (matching.size() > 1 || (!matching.isEmpty() && rows.stream().anyMatch(r -> Objects.equals(r.quarter(), (selection.month() - 1) / 3 + 1)))) return new Value(null, "OVERLAPPING_PERIODS");
      return matching.isEmpty() || expected(matching.get(0), name) == null ? new Value(null, "NOT_CONFIGURED") : new Value(expected(matching.get(0), name), "CONFIGURED");
    }
    BigDecimal sum = BigDecimal.ZERO; boolean missing = false;
    int start = selection.period() == Period.YEARLY ? 1 : selection.quarter(), end = selection.period() == Period.YEARLY ? 4 : start;
    for (int q = start; q <= end; q++) {
      final int quarter = q;
      var quarterly = rows.stream().filter(r -> Objects.equals(r.quarter(), quarter)).toList();
      var monthly = rows.stream().filter(r -> r.month() != null && (r.month() - 1) / 3 + 1 == quarter).toList();
      if (quarterly.size() > 1 || (!quarterly.isEmpty() && !monthly.isEmpty()) || monthly.stream().map(EconomicMetricsRequest::month).distinct().count() != monthly.size()) return new Value(null, "OVERLAPPING_PERIODS");
      if (!quarterly.isEmpty()) {
        var value = expected(quarterly.get(0), name); if (value == null) missing = true; else sum = sum.add(value);
      } else {
        if (monthly.size() != 3) missing = true;
        for (var month : monthly) { var value = expected(month, name); if (value == null) missing = true; else sum = sum.add(value); }
      }
    }
    return missing ? new Value(null, "NOT_CONFIGURED") : new Value(sum, "CONFIGURED");
  }
  private BigDecimal expected(EconomicMetricsRequest r, String kpi) {
    return switch (kpi) { case TOTAL_SALES_VOLUME -> r.totalSalesVolume(); case PROFIT -> r.profit(); case INGREDIENT_COST -> r.ingredientCost(); case PLATFORM_COST -> r.platformCost(); default -> throw new IllegalArgumentException(kpi); };
  }
  private BigDecimal actual(ActualMetrics r, String kpi) {
    return switch (kpi) { case PROFIT -> r.profit(); case INGREDIENT_COST -> r.ingredientCost(); case PLATFORM_COST -> r.platformCost(); default -> throw new IllegalArgumentException(kpi); };
  }
  private long coverage(ActualMetrics r, String kpi) {
    if (r.coverage() == null) return -1;
    return switch (kpi) { case PROFIT -> r.coverage().profitInvoices(); case INGREDIENT_COST -> r.coverage().ingredientCostInvoices(); case PLATFORM_COST -> r.coverage().platformCostInvoices(); default -> 0; };
  }
}
