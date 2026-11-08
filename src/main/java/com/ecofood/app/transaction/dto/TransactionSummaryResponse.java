package com.ecofood.app.transaction.dto;

import java.math.BigDecimal;
import java.util.List;

public record TransactionSummaryResponse(long totalTransactions, List<CurrencyTotal> totalsByCurrency) {
  public record CurrencyTotal(String currency, long totalTransactions, BigDecimal totalAmount) {}
}
