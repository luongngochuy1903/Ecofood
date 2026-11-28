package com.ecofood.app.metrics;

import static com.ecofood.app.metrics.MetricsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ecofood.app.metrics.service.KpiCalculator;
import com.ecofood.app.metrics.service.KpiProcessingException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KpiCalculatorTest {
  private final KpiCalculator calculator = new KpiCalculator();

  @Test
  void onePaidInvoiceIsOnePlanRegardlessOfNumberOfItemsOrQuantities() {
    var invoice = invoice("i", "tenant", "prop", "USD", "100.05");
    var result = calculator.calculate(invoice);
    assertEquals(2, invoice.getItems().size());
    assertEquals(1, result.totalSalesVolume());
    assertEquals(bd("10.00"), result.ingredientCost());
    assertEquals(bd("10.01"), result.platformCost());
    assertEquals(bd("66.04"), result.profit());
    assertTrue(result.complete());
    // Ignores totalAmount=140, targetMargin/tax/totalCost and stale precomputed snapshot results.
    assertEquals(bd("100.05"), invoice.getSnapshot().netRevenue());
  }

  @Test
  void supportsNegativeProfitWithoutDeductingSharesMarginOrTotalCostAgain() {
    var result = calculator.calculate(invoice("i", "tenant", "prop", "USD", "1"));
    assertEquals(bd("0.10"), result.platformCost());
    assertEquals(bd("-23.10"), result.profit());
  }

  @Test
  void missingNetRevenueDoesNotUseTotalAmountOrStoredDerivedValues() {
    var result = calculator.calculate(invoice("i", "tenant", "prop", "USD", null));
    assertEquals(1, result.totalSalesVolume());
    assertEquals(bd("10.00"), result.ingredientCost());
    assertNull(result.platformCost());
    assertNull(result.profit());
    assertFalse(result.complete());
    assertTrue(result.missingData().contains("NET_REVENUE_UNVERIFIED"));
  }

  @Test
  void unverifiedNonNullNetRevenueRemainsUnknown() {
    var invoice = invoice("i", "tenant", "prop", "USD", "100");
    invoice.setSnapshot(snapshot(invoice, bd("100"), invoice.getSnapshot().costing(), bd("10"),
        "tenant", List.of("NET_REVENUE_UNVERIFIED")));
    var result = calculator.calculate(invoice);
    assertNull(result.platformCost());
    assertNull(result.profit());
  }

  @Test
  void onlyUsesPinnedCostingAndDoesNotInventMissingCostComponents() {
    var invoice = invoice("i", "tenant", "prop", "USD", "100");
    invoice.setSnapshot(snapshot(invoice, bd("100"), costing("USD", "40", "2", null, "4", "5"),
        bd("10"), "tenant", List.of()));
    var result = calculator.calculate(invoice);
    assertEquals(bd("40.00"), result.ingredientCost());
    assertEquals(bd("10.00"), result.platformCost());
    assertNull(result.profit());
    assertTrue(result.missingData().contains("LABOR_COST_UNVERIFIED"));
  }

  @Test
  void foreignCurrencyCostingDoesNotBecomeInvoiceCurrencyByRelabelling() {
    var invoice = invoice("i", "tenant", "prop", "USD", "100");
    invoice.setSnapshot(snapshot(invoice, bd("100"), costing("VND", "40", "2", "3", "4", "5"),
        bd("10"), "tenant", List.of()));
    var result = calculator.calculate(invoice);
    assertNull(result.ingredientCost());
    assertNull(result.profit());
    assertEquals(bd("10.00"), result.platformCost());
  }

  @Test
  void missingOrForeignTenantSnapshotStillCountsValidInvoiceWithoutUsingForeignMoney() {
    var invoice = invoice("i", "tenant", "prop", "USD", "100");
    invoice.setSnapshot(snapshot(invoice, bd("100"), invoice.getSnapshot().costing(), bd("10"),
        "other", List.of()));
    var result = calculator.calculate(invoice);
    assertEquals(1, result.totalSalesVolume());
    assertNull(result.ingredientCost());
    assertNull(result.platformCost());
    assertNull(result.profit());
    invoice.setSnapshot(null);
    assertEquals(1, calculator.calculate(invoice).totalSalesVolume());
  }

  @Test
  void invalidInvoiceStatusOrPaymentIsRejectedRatherThanCounted() {
    var invoice = invoice("i", "tenant", "prop", "USD", null);
    invoice.setStatus(null);
    assertThrows(KpiProcessingException.class, () -> calculator.calculate(invoice));
  }

  @Test
  void receivedAtUsesVietnamCalendarAtMonthAndYearBoundariesNotTransactionTime() {
    var invoice = invoice("i", "tenant", "prop", "USD", null);
    invoice.setReceivedAt(Instant.parse("2026-12-31T16:59:59.999Z"));
    var before = calculator.calculate(invoice).key();
    assertEquals(2026, before.year());
    assertEquals(12, before.month());
    invoice.setReceivedAt(Instant.parse("2026-12-31T17:00:00Z"));
    var after = calculator.calculate(invoice).key();
    assertEquals(2027, after.year());
    assertEquals(1, after.month());
    invoice.setReceivedAt(Instant.parse("2026-11-30T17:00:00Z"));
    assertEquals(12, calculator.calculate(invoice).key().month());
  }

  @Test
  void roundsHalfUpToCurrencyPrecisionIncludingZeroAndThreeDecimals() {
    var vnd = calculator.calculate(invoice("i", "tenant", "prop", "VND", "105"));
    assertEquals(bd("11"), vnd.platformCost());
    var kwd = calculator.calculate(invoice("i", "tenant", "prop", "KWD", "1.005"));
    assertEquals(bd("0.101"), kwd.platformCost());
    assertEquals(3, kwd.profit().scale());
  }

  @Test
  void invalidRateDoesNotProduceFakePlatformCostOrProfit() {
    var invoice = invoice("i", "tenant", "prop", "USD", "100");
    invoice.setSnapshot(snapshot(invoice, bd("100"), invoice.getSnapshot().costing(), bd("101"),
        "tenant", List.of()));
    var result = calculator.calculate(invoice);
    assertNull(result.platformCost());
    assertNull(result.profit());
    assertEquals(bd("10.00"), result.ingredientCost());
  }

  @Test
  void fingerprintsAreStableAndScopeAndCurrencyKeysAreDistinct() {
    var a = calculator.calculate(invoice("i", "tenant", "prop", "USD", "100"));
    var again = calculator.calculate(invoice("i", "tenant", "prop", "USD", "100"));
    assertEquals(a, again);
    var otherTenant = calculator.calculate(invoice("i", "other", "prop", "USD", "100"));
    var otherProp = calculator.calculate(invoice("i", "tenant", "other", "USD", "100"));
    var otherCurrency = calculator.calculate(invoice("i", "tenant", "prop", "VND", "100"));
    assertNotEquals(a.key().id(), otherTenant.key().id());
    assertNotEquals(a.key().id(), otherProp.key().id());
    assertNotEquals(a.key().id(), otherCurrency.key().id());
    assertEquals(64, a.inputFingerprint().length());
  }
}
