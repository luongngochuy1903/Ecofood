package com.ecofood.app.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CustomerInvoiceResponse(String id, String invoiceCode,
    String distributionPartnerName, Instant saleDate, String status,
    String currency, BigDecimal totalAmount, int itemCount, List<Item> items) {
  public record Item(String productId, String productName, BigDecimal quantity,
                     BigDecimal unitPrice, BigDecimal lineTotal) {}
}
