package com.ecofood.app.dashboard.service;

import static com.ecofood.core.dto.DashboardDtos.*;

import com.ecofood.app.dashboard.security.DashboardAccess;
import com.ecofood.app.metrics.entity.ActualMetrics;
import com.ecofood.core.dto.EconomicMetricsRequest;
import com.ecofood.core.enums.DashboardPeriodEnum;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Aggregates stored KPI values only; this is not another financial calculator. */
@Component
public class DashboardAggregation {
  private static final String CONFIGURED = "CONFIGURED";
  private static final String NOT_CONFIGURED = "NOT_CONFIGURED";
  private static final String OVERLAPPING_PERIODS = "OVERLAPPING_PERIODS";
  private static final String PROFIT = "profit";
  private static final String TOTAL_SALES_VOLUME = "totalSalesVolume";
  private static final String INGREDIENT_COST = "ingredientCost";
  private static final String PLATFORM_COST = "platformCost";
  public static final List<String> KPIS = List.of(TOTAL_SALES_VOLUME, PROFIT, INGREDIENT_COST, PLATFORM_COST);
  public record Target(String currency, List<EconomicMetricsRequest> rows) {}
  private record Value(BigDecimal amount, String status) {}
  private record ActualValue(BigDecimal amount, String status, long covered) {}
  public Summary summarize(Selection selection, Set<String> tenants, List<Target> targets, List<ActualMetrics> input) {
    var rows = matchingRows(selection, tenants, input);
    long volume = salesVolume(rows);
    Map<String, Metric> metrics = new LinkedHashMap<>();
    for (String name : KPIS) metrics.put(name, metric(selection, targets, rows, volume, name));
    Instant latest = rows.stream().map(ActualMetrics::updatedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    return new Summary(selection, tenants.stream().sorted().toList(), metrics, latest, "Asia/Ho_Chi_Minh",
        List.of("Actuals are read from Proposition.actualMetrics and committed with accepted sales.",
                "Expected is read from current Proposition configuration; no Expected configuration history is available."));
  }
  private List<ActualMetrics> matchingRows(Selection selection, Set<String> tenants, List<ActualMetrics> input) {
    return input.stream().filter(r -> tenants.contains(r.tenantId()) && r.year() == selection.year()
        && selection.currency().equals(r.currency()) && selection.months().contains(r.month())
        && (selection.propositionId() == null || selection.propositionId().equals(r.propositionId()))).toList();
  }
  private long salesVolume(List<ActualMetrics> rows) {
    Set<String> buckets = new HashSet<>();
    long volume = 0;
    for (var row : rows) {
      String key = row.propositionId() + "/" + row.tenantId() + "/" + row.year() + "/" + row.month() + "/" + row.currency();
      if (!buckets.add(key) || row.totalSalesVolume() < 0) throw DashboardAccess.error(HttpStatus.CONFLICT, "Inconsistent ActualMetrics buckets");
      volume = Math.addExact(volume, row.totalSalesVolume());
    }
    return volume;
  }
  private Metric metric(Selection selection, List<Target> targets, List<ActualMetrics> rows, long volume, String name) {
    boolean count = name.equals(TOTAL_SALES_VOLUME);
    boolean cost = name.endsWith("Cost");
    ActualValue actual = actualValue(rows, volume, name);
    Value expected = expected(targets, selection, name);
    BigDecimal variance = actual.amount() != null && expected.amount() != null ? actual.amount().subtract(expected.amount()) : null;
    BigDecimal achievement = !cost && expected.amount() != null && expected.amount().signum() > 0 && actual.amount() != null
        ? percent(actual.amount(), expected.amount()) : null;
    BigDecimal variancePercent = variance != null && expected.amount().signum() != 0 ? percent(variance, expected.amount().abs()) : null;
    return new Metric(actual.amount(), expected.amount(), variance, achievement, variancePercent,
        actual.status(), expected.status(), count ? "PLAN" : selection.currency(),
        cost ? "BUDGET_VARIANCE" : "TARGET_ACHIEVEMENT", actual.covered(), volume);
  }
  private ActualValue actualValue(List<ActualMetrics> rows, long volume, String name) {
    if (name.equals(TOTAL_SALES_VOLUME)) {
      return new ActualValue(BigDecimal.valueOf(volume), actualStatus(volume, true), volume);
    }
    BigDecimal sum = BigDecimal.ZERO;
    long covered = 0;
    boolean complete = true;
    for (var row : rows) {
      BigDecimal value = actual(row, name);
      long coverage = coverage(row, name);
      if (coverage >= 0 && coverage <= row.totalSalesVolume()) covered = Math.addExact(covered, coverage);
      if (value == null || coverage != row.totalSalesVolume()) complete = false;
      else sum = sum.add(value);
    }
    return new ActualValue(complete && volume != 0 ? sum : null, actualStatus(volume, complete), covered);
  }
  private String actualStatus(long volume, boolean complete) {
    if (volume == 0) return "NO_RECORDED_TRANSACTIONS";
    return complete ? "COMPLETE" : "INCOMPLETE";
  }
  private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) { return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP); }
  private Value expected(List<Target> targets, Selection selection, String name) {
    if (targets.isEmpty()) return new Value(null, NOT_CONFIGURED);
    BigDecimal total = BigDecimal.ZERO;
    boolean missing = false;
    for (var target : targets) {
      var value = expectedTarget(target, selection, name);
      if (value.status().equals("CURRENCY_UNVERIFIED") || value.status().equals("CURRENCY_MISMATCH")) return value;
      if (value.status().equals(OVERLAPPING_PERIODS) || value.status().equals("INVALID_PERIOD")) return value;
      if (value.amount() == null) missing = true; else total = total.add(value.amount());
    }
    return missing ? new Value(null, "NOT_FULLY_CONFIGURED") : new Value(total, CONFIGURED);
  }
  private Value expectedTarget(Target target, Selection selection, String name) {
    if (target.currency() == null || target.currency().isBlank()) return new Value(null, "CURRENCY_UNVERIFIED");
    if (!selection.currency().equals(target.currency())) return new Value(null, "CURRENCY_MISMATCH");
    return expectedPeriod(target.rows() == null ? List.of() : target.rows(), selection, name);
  }
  private Value expectedPeriod(List<EconomicMetricsRequest> input, Selection selection, String name) {
    var rows = input.stream().filter(r -> r != null && Objects.equals(r.year(), selection.year())).toList();
    if (rows.stream().anyMatch(this::invalidPeriod)) return new Value(null, "INVALID_PERIOD");
    if (selection.period() == DashboardPeriodEnum.MONTHLY) return monthlyExpected(rows, selection.month(), name);
    int start = selection.period() == DashboardPeriodEnum.YEARLY ? 1 : selection.quarter();
    int end = selection.period() == DashboardPeriodEnum.YEARLY ? 4 : start;
    BigDecimal sum = BigDecimal.ZERO;
    boolean missing = false;
    for (int quarter = start; quarter <= end; quarter++) {
      Value value = quarterExpected(rows, quarter, name);
      if (value.status().equals(OVERLAPPING_PERIODS)) return value;
      if (value.amount() == null) missing = true;
      else sum = sum.add(value.amount());
    }
    return missing ? new Value(null, NOT_CONFIGURED) : new Value(sum, CONFIGURED);
  }
  private boolean invalidPeriod(EconomicMetricsRequest row) {
    if ((row.month() == null) == (row.quarter() == null)) return true;
    if (row.month() != null) return row.month() < 1 || row.month() > 12;
    return row.quarter() < 1 || row.quarter() > 4;
  }
  private Value monthlyExpected(List<EconomicMetricsRequest> rows, int month, String name) {
    var matching = rows.stream().filter(r -> Objects.equals(r.month(), month)).toList();
    boolean quarterly = rows.stream().anyMatch(r -> Objects.equals(r.quarter(), (month - 1) / 3 + 1));
    if (matching.size() > 1 || (!matching.isEmpty() && quarterly)) return new Value(null, OVERLAPPING_PERIODS);
    if (matching.isEmpty()) return new Value(null, NOT_CONFIGURED);
    return configuredValue(expected(matching.get(0), name));
  }
  private Value quarterExpected(List<EconomicMetricsRequest> rows, int quarter, String name) {
    var quarterly = rows.stream().filter(r -> Objects.equals(r.quarter(), quarter)).toList();
    var monthly = rows.stream().filter(r -> r.month() != null && (r.month() - 1) / 3 + 1 == quarter).toList();
    if (quarterly.size() > 1 || (!quarterly.isEmpty() && !monthly.isEmpty())
        || monthly.stream().map(EconomicMetricsRequest::month).distinct().count() != monthly.size()) {
      return new Value(null, OVERLAPPING_PERIODS);
    }
    if (!quarterly.isEmpty()) return configuredValue(expected(quarterly.get(0), name));
    return monthlyQuarterExpected(monthly, name);
  }
  private Value monthlyQuarterExpected(List<EconomicMetricsRequest> monthly, String name) {
    BigDecimal sum = BigDecimal.ZERO;
    boolean missing = monthly.size() != 3;
    for (var month : monthly) {
      BigDecimal value = expected(month, name);
      if (value == null) missing = true;
      else sum = sum.add(value);
    }
    return missing ? new Value(null, NOT_CONFIGURED) : new Value(sum, CONFIGURED);
  }
  private Value configuredValue(BigDecimal amount) {
    return amount == null ? new Value(null, NOT_CONFIGURED) : new Value(amount, CONFIGURED);
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
