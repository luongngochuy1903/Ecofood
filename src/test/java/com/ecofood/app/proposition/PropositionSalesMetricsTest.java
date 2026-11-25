package com.ecofood.app.proposition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.service.PropositionSalesMetrics;
import com.ecofood.app.transaction.entity.*;
import com.ecofood.core.dto.EconomicMetricsRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

class PropositionSalesMetricsTest {
  private static final String PROP = "111111111111111111111111";
  private final MongoOperations ops = mock(MongoOperations.class);
  private final PropositionSalesMetrics metrics = new PropositionSalesMetrics();

  private PropositionEntity proposition() {
    var p = new PropositionEntity();
    p.setId(new ObjectId(PROP));
    p.setActualMetricsVersion(PropositionSalesMetrics.VERSION);
    when(ops.findById(p.getId(), PropositionEntity.class)).thenReturn(p);
    return p;
  }

  private Invoice invoice(String tenant, String currency, String time, long quantity) {
    var snapshot = mock(InvoiceSnapshot.class);
    when(snapshot.propositionId()).thenReturn(PROP);
    var costing = mock(InvoiceSnapshot.Costing.class);
    when(costing.ingredientCost()).thenReturn(new BigDecimal("5"));
    when(costing.packagingCost()).thenReturn(new BigDecimal("2"));
    when(costing.laborCost()).thenReturn(new BigDecimal("3"));
    when(costing.operationCost()).thenReturn(new BigDecimal("4"));
    when(costing.marketingFee()).thenReturn(BigDecimal.ONE);
    when(snapshot.costing()).thenReturn(costing);
    var shares = mock(InvoiceSnapshot.ProfitShare.class);
    when(shares.platformProfitShare()).thenReturn(new BigDecimal("50"));
    when(snapshot.profitShare()).thenReturn(shares);
    var invoice = new Invoice();
    invoice.setTenantId(tenant);
    invoice.setCurrency(currency);
    invoice.setReceivedAt(Instant.parse(time));
    invoice.setPlanLines(List.of(new InvoicePlanLine("line", "orch", "plan", "Plan", quantity,
        new BigDecimal("50"), new BigDecimal("50").multiply(BigDecimal.valueOf(quantity)),
        snapshot, true, null)));
    return invoice;
  }

  @Test
  void accumulatesQuantityRevenueAndSnapshotCostsInTheSameProposition() {
    var p = proposition();
    metrics.record(ops, List.of(invoice("partner", "USD", "2026-11-25T10:00:00Z", 2),
        invoice("partner", "USD", "2026-11-26T10:00:00Z", 1)));
    var row = p.getActualMetrics().get(0);
    assertEquals(1, p.getActualMetrics().size());
    assertEquals("partner", row.tenantId());
    assertEquals("USD", row.currency());
    assertEquals(11, row.month());
    assertNull(row.quarter());
    assertEquals(0, new BigDecimal("3").compareTo(row.totalSalesVolume()));
    assertEquals(0, new BigDecimal("150").compareTo(row.revenue()));
    assertEquals(0, new BigDecimal("15").compareTo(row.ingredientCost()));
    assertEquals(0, new BigDecimal("75").compareTo(row.platformCost()));
    assertEquals(0, new BigDecimal("30").compareTo(row.profit()));
    verify(ops).save(p);
  }

  @Test
  void keepsTenantsCurrenciesAndAccountingMonthsSeparate() {
    var p = proposition();
    metrics.record(ops, List.of(invoice("a", "USD", "2026-11-30T18:00:00Z", 1),
        invoice("a", "USD", "2026-11-30T16:00:00Z", 1),
        invoice("a", "VND", "2026-11-30T16:00:00Z", 1),
        invoice("b", "USD", "2026-11-30T16:00:00Z", 1)));
    assertEquals(4, p.getActualMetrics().size());
    assertTrue(p.getActualMetrics().stream().anyMatch(r -> r.month() == 12));
    assertTrue(p.getActualMetrics().stream().anyMatch(r -> r.month() == 11));
  }

  @Test
  void rebuildReplacesOldMonetaryVolumeInsteadOfAddingItToNewUnitCounts() {
    var p = proposition();
    p.setActualMetricsVersion(null);
    p.setActualMetrics(List.of(new EconomicMetricsRequest(null, 2026, new BigDecimal("99999"),
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 4, new BigDecimal("99999"))));
    var historical = invoice("partner", "USD", "2026-11-25T10:00:00Z", 2);
    when(ops.find(any(Query.class), eq(Invoice.class))).thenReturn(List.of(historical));
    metrics.record(ops, List.of(invoice("partner", "USD", "2026-11-26T10:00:00Z", 1)));
    assertEquals(1, p.getActualMetrics().size());
    assertEquals(0, new BigDecimal("3").compareTo(p.getActualMetrics().get(0).totalSalesVolume()));
    assertEquals(PropositionSalesMetrics.VERSION, p.getActualMetricsVersion());
  }

  @Test
  void repeatedBackfillDoesNotDoubleCountAndKeepsLegacyRowsWhenNoSourceExists() {
    var p = proposition();
    var historical = invoice("partner", "USD", "2026-11-25T10:00:00Z", 2);
    when(ops.find(any(Query.class), eq(Invoice.class)))
        .thenReturn(List.of(historical));
    metrics.rebuild(ops, p);
    var expected = p.getActualMetrics();
    metrics.rebuild(ops, p);
    assertEquals(expected, p.getActualMetrics());
    when(ops.find(any(Query.class), eq(Invoice.class))).thenReturn(List.of());
    metrics.rebuild(ops, p);
    assertEquals(expected, p.getActualMetrics());
  }
}
