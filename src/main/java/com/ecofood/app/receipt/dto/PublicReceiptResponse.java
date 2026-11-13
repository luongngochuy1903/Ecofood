package com.ecofood.app.receipt.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Explicit guest allowlist; never serialize Invoice or ReceiptAccess directly. */
public record PublicReceiptResponse(String invoiceNumber, Instant transactionTime,
    String distributionPartner, String currency, BigDecimal totalAmount, List<Item> items) {
  public record Item(String productCode, String productName, BigDecimal quantity,
      BigDecimal unitPrice, BigDecimal amount) {}
}
