package com.ecofood.app.metrics.entity;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Immutable audit of one Invoice, embedded in its existing Submission, not a separate ledger. */
public record KpiContribution(
    String invoiceId,
    MetricsKey key,
    String calculatorVersion,
    String inputFingerprint,
    long totalSalesVolume,
    @Field(targetType = FieldType.DECIMAL128) BigDecimal ingredientCost,
    @Field(targetType = FieldType.DECIMAL128) BigDecimal platformCost,
    @Field(targetType = FieldType.DECIMAL128) BigDecimal profit,
    List<String> missingData) {
  public KpiContribution { missingData = List.copyOf(missingData); }
  public boolean complete() {
    return ingredientCost != null && platformCost != null && profit != null;
  }
}
