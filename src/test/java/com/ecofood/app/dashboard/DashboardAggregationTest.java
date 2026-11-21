package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static com.ecofood.app.dashboard.DashboardDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class DashboardAggregationTest {
  final DashboardAggregation calculator = new DashboardAggregation();
  DashboardAggregation.Target target(List<com.ecofood.core.dto.EconomicMetricsRequest> rows) { return new DashboardAggregation.Target("USD", rows); }
  @Test void monthToQuarterSumsStoredValuesAndNegativeProfit() {
    var summary = calculator.summarize(selection(Period.QUARTERLY), Set.of(A), List.of(target(List.of(quarter(1, "10")))),
        List.of(actual(A, 1, "USD", 1, "-5"), actual(A, 2, "USD", 2, "2"), actual(A, 3, "USD", 3, "0"), actual(A, 4, "USD", 100, "999")));
    assertEquals(bd("6"), summary.metrics().get("totalSalesVolume").actual()); assertEquals(bd("-3"), summary.metrics().get("profit").actual());
    assertEquals(bd("-30.00"), summary.metrics().get("profit").achievementPercent());
  }
  @Test void quarterExpectedIsNeverDividedIntoMonths() {
    var metric = calculator.summarize(selection(Period.MONTHLY), Set.of(A), List.of(target(List.of(quarter(1, "90")))), List.of()).metrics().get("profit");
    assertNull(metric.expected()); assertNull(metric.achievementPercent());
  }
  @Test void fullMonthlyExpectedCanRollUpToQuarter() {
    var metric = calculator.summarize(selection(Period.QUARTERLY), Set.of(A), List.of(target(List.of(month(1, "1"), month(2, "2"), month(3, "3")))), List.of()).metrics().get("profit");
    assertEquals(bd("6"), metric.expected());
  }
  @Test void fourQuartersRollUpToYearAndMissingQuarterStaysUnknown() {
    var all = List.of(quarter(1, "1"), quarter(2, "2"), quarter(3, "3"), quarter(4, "4"));
    assertEquals(bd("10"), calculator.summarize(selection(Period.YEARLY), Set.of(A), List.of(target(all)), List.of()).metrics().get("profit").expected());
    assertNull(calculator.summarize(selection(Period.YEARLY), Set.of(A), List.of(target(all.subList(0, 3))), List.of()).metrics().get("profit").expected());
  }
  @Test void overlappingQuarterAndMonthIsNotDoubleCounted() {
    var metric = calculator.summarize(selection(Period.QUARTERLY), Set.of(A), List.of(target(List.of(quarter(1, "90"), month(1, "30")))), List.of()).metrics().get("profit");
    assertNull(metric.expected()); assertEquals("OVERLAPPING_PERIODS", metric.expectedStatus());
  }
  @Test void actualCurrencyAndTenantRemainSeparated() {
    var row = calculator.summarize(selection(Period.MONTHLY), Set.of(A), List.of(), List.of(actual(A, 1, "USD", 1, "10"), actual(A, 1, "VND", 500, "500"), actual(B, 1, "USD", 900, "900")));
    assertEquals(bd("1"), row.metrics().get("totalSalesVolume").actual()); assertEquals(bd("10"), row.metrics().get("profit").actual());
  }
  @Test void partialFinancialBucketDoesNotPublishKnownPartialSumAsTotal() {
    var row = calculator.summarize(selection(Period.QUARTERLY), Set.of(A), List.of(), List.of(actual(A, 1, "USD", 1, "20"), actual(A, 2, "USD", 2, null)));
    assertNull(row.metrics().get("profit").actual()); assertEquals("INCOMPLETE", row.metrics().get("profit").actualStatus());
    assertEquals(1, row.metrics().get("profit").coveredInvoices()); assertEquals(3, row.metrics().get("profit").totalInvoices());
    assertEquals(bd("3"), row.metrics().get("totalSalesVolume").actual()); assertEquals(bd("4"), row.metrics().get("ingredientCost").actual());
  }
  @Test void missingExpectedDoesNotCauseDivisionAndZeroTargetIsPreserved() {
    var zero = calculator.summarize(selection(Period.QUARTERLY), Set.of(A), List.of(target(List.of(quarter(1, "0")))), List.of(actual(A, 1, "USD", 1, "-2"))).metrics().get("profit");
    assertEquals(BigDecimal.ZERO, zero.expected()); assertNull(zero.achievementPercent()); assertNull(zero.variancePercent()); assertEquals(bd("-2"), zero.variance());
    var missing = calculator.summarize(selection(Period.QUARTERLY), Set.of(A), List.of(), List.of()).metrics().get("profit"); assertNull(missing.expected()); assertNull(missing.actual());
  }
  @Test void costsHaveBudgetVarianceWithoutAchievement() {
    var metric = calculator.summarize(selection(Period.QUARTERLY), Set.of(A), List.of(target(List.of(quarter(1, "1")))), List.of(actual(A, 1, "USD", 1, "0"))).metrics().get("ingredientCost");
    assertEquals(bd("1"), metric.variance()); assertEquals(bd("100.00"), metric.variancePercent()); assertNull(metric.achievementPercent()); assertEquals("BUDGET_VARIANCE", metric.interpretation());
  }
  @Test void realZeroProfitDiffersFromNoRecordedTransactions() {
    var zero = calculator.summarize(selection(Period.MONTHLY), Set.of(A), List.of(), List.of(actual(A, 1, "USD", 1, "0"))).metrics().get("profit");
    assertEquals(BigDecimal.ZERO, zero.actual()); assertEquals("COMPLETE", zero.actualStatus());
    var empty = calculator.summarize(selection(Period.MONTHLY), Set.of(A), List.of(), List.of());
    assertNull(empty.metrics().get("profit").actual()); assertEquals("NO_RECORDED_TRANSACTIONS", empty.metrics().get("profit").actualStatus()); assertEquals(BigDecimal.ZERO, empty.metrics().get("totalSalesVolume").actual());
  }
  @Test void unknownExpectedCurrencyDoesNotBecomeSelectedCurrency() {
    var targets = List.of(new DashboardAggregation.Target(null, List.of(quarter(1, "10"))));
    var metric = calculator.summarize(selection(Period.QUARTERLY), Set.of(A), targets, List.of()).metrics().get("profit"); assertNull(metric.expected()); assertEquals("CURRENCY_UNVERIFIED", metric.expectedStatus());
  }
  @Test void duplicateProjectionBucketIsRejectedInsteadOfCountedTwice() {
    var row = actual(A, 1, "USD", 1, "1"); assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> calculator.summarize(selection(Period.MONTHLY), Set.of(A), List.of(), List.of(row, row)));
  }
  @Test void blankAndMismatchedTargetCurrenciesKeepTheirFailureStatus() {
    var blank = new DashboardAggregation.Target(" ", List.of(quarter(1, "10")));
    var foreign = new DashboardAggregation.Target("VND", List.of(quarter(1, "10")));
    var selected = selection(Period.QUARTERLY);
    var unverified = calculator.summarize(selected, Set.of(A), List.of(blank), List.of()).metrics().get("profit");
    var mismatch = calculator.summarize(selected, Set.of(A), List.of(foreign), List.of()).metrics().get("profit");
    assertNull(unverified.expected()); assertEquals("CURRENCY_UNVERIFIED", unverified.expectedStatus());
    assertNull(mismatch.expected()); assertEquals("CURRENCY_MISMATCH", mismatch.expectedStatus());
  }
  @Test void missingTargetRowsDoNotHideInvalidCurrencyInLaterTarget() {
    var missing = new DashboardAggregation.Target("USD", null);
    var foreign = new DashboardAggregation.Target("VND", List.of(quarter(1, "10")));
    var selected = selection(Period.QUARTERLY);
    var incomplete = calculator.summarize(selected, Set.of(A), List.of(missing), List.of()).metrics().get("profit");
    var mismatch = calculator.summarize(selected, Set.of(A), List.of(missing, foreign), List.of()).metrics().get("profit");
    assertNull(incomplete.expected()); assertEquals("NOT_FULLY_CONFIGURED", incomplete.expectedStatus());
    assertNull(mismatch.expected()); assertEquals("CURRENCY_MISMATCH", mismatch.expectedStatus());
  }
}
