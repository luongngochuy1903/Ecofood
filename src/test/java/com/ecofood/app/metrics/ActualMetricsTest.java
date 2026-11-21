package com.ecofood.app.metrics;

import static com.ecofood.app.metrics.MetricsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ecofood.app.metrics.entity.ActualMetrics;
import com.ecofood.app.metrics.service.KpiCalculator;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ActualMetricsTest {
  private final KpiCalculator calculator = new KpiCalculator();

  @Test
  void unknownMoneyIsNullWithCoverageAndExplicitKnownSubtotals() {
    var full = calculator.calculate(invoice("i1", "tenant", "prop", "USD", "100"));
    var partial = calculator.calculate(invoice("i2", "tenant", "prop", "USD", null));
    var row = ActualMetrics.empty(full.key(), KpiCalculator.VERSION, Instant.now())
        .add(full, Instant.now()).add(partial, Instant.now());
    assertEquals(2, row.totalSalesVolume());
    assertEquals(bd("20.00"), row.ingredientCost());
    assertNull(row.platformCost());
    assertNull(row.profit());
    assertEquals(bd("10.00"), row.knownTotals().platformCost());
    assertEquals(bd("66.00"), row.knownTotals().profit());
    assertEquals(1, row.coverage().platformCostInvoices());
    assertEquals(1, row.coverage().profitInvoices());
    assertEquals(1, row.coverage().incompleteInvoices());
  }

  @Test
  void absentCostDoesNotBecomeZeroAndRealZeroRemainsDistinguishable() {
    var invoice = invoice("i", "tenant", "prop", "USD", null);
    invoice.setSnapshot(null);
    var missing = calculator.calculate(invoice);
    var row = ActualMetrics.empty(missing.key(), KpiCalculator.VERSION, Instant.now()).add(missing, Instant.now());
    assertNull(row.ingredientCost());
    assertEquals(0, row.coverage().ingredientCostInvoices());
    assertEquals(0, row.knownTotals().ingredientCost().signum());
    assertEquals(1, row.coverage().incompleteInvoices());
  }

  @Test
  void rejectsCombiningDifferentTenantsOrCurrencies() {
    var a = calculator.calculate(invoice("i1", "tenant", "prop", "USD", null));
    var row = ActualMetrics.empty(a.key(), KpiCalculator.VERSION, Instant.now());
    var other = calculator.calculate(invoice("i2", "other", "prop", "USD", null));
    assertThrows(IllegalStateException.class, () -> row.add(other, Instant.now()));
    var currency = calculator.calculate(invoice("i3", "tenant", "prop", "VND", null));
    assertThrows(IllegalStateException.class, () -> row.add(currency, Instant.now()));
  }
}
