package com.ecofood.app.metrics.entity;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Document("actual_metrics")
public record ActualMetrics(
    @Id String id,
    String propositionId,
    String tenantId,
    int year,
    int month,
    String currency,
    long totalSalesVolume,
    @Field(targetType = FieldType.DECIMAL128) BigDecimal ingredientCost,
    @Field(targetType = FieldType.DECIMAL128) BigDecimal platformCost,
    @Field(targetType = FieldType.DECIMAL128) BigDecimal profit,
    KnownTotals knownTotals,
    Coverage coverage,
    String calculatorVersion,
    long revision,
    Instant updatedAt) {

  /** Partial sums are explicitly named; a top-level KPI is null unless all sold Plan units are covered. */
  public record KnownTotals(
      @Field(targetType = FieldType.DECIMAL128) BigDecimal ingredientCost,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal platformCost,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal profit) {}

  /** Legacy field names retained. Coverage counts Plan units (one per legacy Invoice). */
  public record Coverage(long ingredientCostInvoices, long platformCostInvoices,
                         long profitInvoices, long incompleteInvoices) {}

  public MetricsKey key() { return new MetricsKey(propositionId, tenantId, year, month, currency); }

  public static ActualMetrics empty(MetricsKey key, String version, Instant now) {
    return new ActualMetrics(key.id(), key.propositionId(), key.tenantId(), key.year(), key.month(),
        key.currency(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        new KnownTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
        new Coverage(0, 0, 0, 0), version, 0, now);
  }

  /** Must only be persisted together with the corresponding Submission marker in a transaction. */
  public ActualMetrics add(KpiContribution contribution, Instant now) {
    if (!key().equals(contribution.key()) || contribution.totalSalesVolume() < 1
        || !calculatorVersion.equals(contribution.calculatorVersion()))
      throw new IllegalStateException("Contribution key or calculator version mismatch");
    long units = contribution.totalSalesVolume();
    long volume = Math.addExact(totalSalesVolume, units);
    var sums = new KnownTotals(addKnown(knownTotals.ingredientCost(), contribution.ingredientCost()),
        addKnown(knownTotals.platformCost(), contribution.platformCost()),
        addKnown(knownTotals.profit(), contribution.profit()));
    var counts = new Coverage(
        Math.addExact(coverage.ingredientCostInvoices(), contribution.ingredientCost() == null ? 0 : units),
        Math.addExact(coverage.platformCostInvoices(), contribution.platformCost() == null ? 0 : units),
        Math.addExact(coverage.profitInvoices(), contribution.profit() == null ? 0 : units),
        Math.addExact(coverage.incompleteInvoices(), contribution.complete() ? 0 : units));
    return new ActualMetrics(id, propositionId, tenantId, year, month, currency, volume,
        counts.ingredientCostInvoices() == volume ? sums.ingredientCost() : null,
        counts.platformCostInvoices() == volume ? sums.platformCost() : null,
        counts.profitInvoices() == volume ? sums.profit() : null,
        sums, counts, calculatorVersion, Math.addExact(revision, 1), now);
  }

  private static BigDecimal addKnown(BigDecimal sum, BigDecimal value) {
    return value == null ? sum : sum.add(value);
  }
}
