package com.ecofood.app.customer.dto;

import java.math.BigDecimal;
import java.util.List;

/** Report totals by currency; adding mixed currencies would be misleading. */
public record CustomerStatisticsResponse(long totalInvoices, List<CurrencySpend> spending) {
  public record CurrencySpend(String currency, long invoiceCount, BigDecimal totalAmount) {}
}
