package com.ecofood.app.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The one submit-transaction contract — a Sale Orchestrator's own Plan list
 * resolves which Orchestrator an item belongs to (see BatchSalePlanResolver),
 * so the caller never names one directly. `actualPrice` is optional: a
 * factory-style Enterprise omits it and the Plan's own defined selling price
 * applies; a Brand running its own storefront supplies the price it actually
 * sold at, to compare against the Plan's defined price. `additionalInfo` is
 * free-form beyond that: whichever extra keys the owning Sale Orchestrator
 * configured (see PlanSaleOrchestratorRequest.additionalInfoFields) — a
 * required one must be present here or the item is rejected. `invoiceHolder`
 * is the buyer's own info, carried once per invoice rather than per item —
 * whichever keys the owning Sale Orchestrator(s) configured (see
 * PlanSaleOrchestratorRequest.invoiceHolder); a required one must be present
 * here or the whole invoice is rejected.
 *
 * A Receipt carries no caller-supplied invoice identifier — the Sale
 * Orchestrator never defines one, so nothing here depends on it. Each
 * ReceiptResult in the Response matches its Receipt in the Request by
 * position (same index), not by any id; a resubmitted batch is deduplicated
 * purely by matching the whole batch's content (see
 * BatchTransactionService), not by a per-invoice number.
 */
public final class BatchTransactionDtos {
  private BatchTransactionDtos() {}
  public record Request(@NotNull @Valid Transaction transaction) {}
  public record Transaction(@NotEmpty @Size(max=100) List<@NotNull @Valid Receipt> invoices) {}
  public record Receipt(Map<String, Object> invoiceHolder,
      @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items) {}
  public record Item(@NotBlank @Size(max=100) String planId,
      @NotNull @DecimalMin("1") @DecimalMax("1000000") @Digits(integer=7,fraction=0) BigDecimal quantity,
      @org.springframework.data.mongodb.core.mapping.Field(targetType=org.springframework.data.mongodb.core.mapping.FieldType.DECIMAL128)
      @DecimalMin("0") @Digits(integer=15,fraction=8) BigDecimal actualPrice,
      Map<String, Object> additionalInfo) {
    public Item(String planId,long quantity,BigDecimal actualPrice) {
      this(planId,BigDecimal.valueOf(quantity),actualPrice,null);
    }
  }
  public record Response(Result transaction) {}
  public record Result(List<ReceiptResult> invoices) {}
  public record ReceiptResult(List<Item> items, String result, Error error) {}
  public record Error(String code, String message) {}
}
