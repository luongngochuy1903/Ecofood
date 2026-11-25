package com.ecofood.app.transaction.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Values captured from the Plan's own pinned Product version, never from Product.latestVersion. */
public record InvoiceSnapshot(
    int schemaVersion,
    String propositionId,
    Long propositionRevision,
    String tenantId,
    String distributionPartnerId,
    String planId,
    int planVersion,
    String orchestratorId,
    int orchestratorVersion,
    String productId,
    String productProcessId,
    int productVersion,
    Long productProcessRevision,
    Costing costing,
    ProfitShare profitShare,
    Market market,
    String currency,
    Instant capturedAt,
    String accountingTimezone,
    int accountingMonth,
    int accountingYear,
    String kpiCalculationStatus,
    List<String> missingData,
    @Field(targetType = FieldType.DECIMAL128) BigDecimal netRevenue,
    @Field(targetType = FieldType.DECIMAL128) BigDecimal platformCost,
    @Field(targetType = FieldType.DECIMAL128) BigDecimal profit) {

  public InvoiceSnapshot {
    missingData = List.copyOf(missingData);
  }

  public record Costing(
      String attributeId,
      String productProcessId,
      Long processRevision,
      String currency,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal ingredientCost,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal packagingCost,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal laborCost,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal operationCost,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal marketingFee,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal targetMargin,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal tax,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal totalCost,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal basePrice,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal sellingPrice) {}

  public record ProfitShare(
      @Field(targetType = FieldType.DECIMAL128) BigDecimal enterpriseProfitShare,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal platformProfitShare,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal distributionProfitShare) {}

  /** Approved price configuration, not evidence of the actual transaction's tax breakdown. */
  public record Market(
      String currency,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal salePrice,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal taxRate,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal surcharge,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal taxAmount,
      @Field(targetType = FieldType.DECIMAL128) BigDecimal totalPrice) {}
}
