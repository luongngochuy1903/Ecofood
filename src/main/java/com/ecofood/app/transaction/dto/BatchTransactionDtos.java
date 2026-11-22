package com.ecofood.app.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** Separate batch contract; the existing UC-015 payload/schema is unchanged. */
public final class BatchTransactionDtos {
  private BatchTransactionDtos() {}
  public record Request(@NotNull @Valid Transaction transaction) {}
  public record Transaction(@NotEmpty @Size(max=100) List<@NotNull @Valid Receipt> invoices) {}
  public record Receipt(@NotBlank @Size(max=100) @Pattern(regexp="\\S(?:.*\\S)?") String invoiceNumber,
      @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items) {}
  public record Item(@NotBlank @Size(max=100) String lineId, @NotBlank @Size(max=100) String orchestratorId,
      @NotBlank @Size(max=100) String planId,
      @NotNull @DecimalMin("1") @DecimalMax("1000000") @Digits(integer=7,fraction=0) BigDecimal quantity,
      @org.springframework.data.mongodb.core.mapping.Field(targetType=org.springframework.data.mongodb.core.mapping.FieldType.DECIMAL128)
      @NotNull @DecimalMin("0") @Digits(integer=15,fraction=8) BigDecimal unitPrice,
      @org.springframework.data.mongodb.core.mapping.Field(targetType=org.springframework.data.mongodb.core.mapping.FieldType.DECIMAL128)
      @NotNull @DecimalMin("0") @Digits(integer=21,fraction=8) BigDecimal amount) {
    public Item(String lineId,String orchestratorId,String planId,long quantity,BigDecimal unitPrice,BigDecimal amount) {
      this(lineId,orchestratorId,planId,BigDecimal.valueOf(quantity),unitPrice,amount);
    }
  }
  public record Response(Result transaction) {}
  public record Result(List<ReceiptResult> invoices) {}
  public record ReceiptResult(String invoiceNumber, List<Item> items, String result, Error error) {}
  public record Error(String code, String message) {}
}
