package com.ecofood.app.customer.dto;

import java.math.BigDecimal;
import java.util.List;
import java.time.Instant;

/** Report totals by currency; adding mixed currencies would be misleading. */
public record CustomerStatisticsResponse(long totalInvoices, List<CurrencySpend> spending,
    long totalOrders, List<CurrencySpend> totalSpent, List<MonthlySpend> monthlySpending,
    PurchaseFrequency purchaseFrequency, List<RecentPurchase> recentPurchases,
    List<PartnerPurchases> distributionPartners, String timeZone, SpendingPolicy spendingPolicy) {
  /** Retain the existing contract/constructor; monetary totals are always partitioned by currency. */
  public CustomerStatisticsResponse(long totalInvoices, List<CurrencySpend> spending) {
    this(totalInvoices, spending, totalInvoices, spending, List.of(), new PurchaseFrequency(0, 0, BigDecimal.ZERO, null, null),
        List.of(), List.of(), "UTC", SpendingPolicy.current());
  }
  public record CurrencySpend(String currency, long invoiceCount, BigDecimal totalAmount) {}
  public record MonthlySpend(String month, String currency, long orderCount, BigDecimal totalAmount) {}
  /** Denominator includes gaps between the first and last purchase month, within the selected data. */
  public record PurchaseFrequency(long activeMonths, long observedMonths, BigDecimal ordersPerMonth,
      Instant firstPurchaseAt, Instant lastPurchaseAt) {}
  public record RecentPurchase(String invoiceId, String invoiceCode, String distributionPartnerId,
      Instant transactionTime, String status, String currency, BigDecimal totalAmount) {}
  public record PartnerPurchases(String sellerTenantId, String distributionPartnerId, long totalOrders, List<CurrencySpend> spending) {}
  public record SpendingPolicy(List<String> includedTransactionStatuses, boolean paymentStatusAvailable, boolean refundTrackingAvailable) {
    public static SpendingPolicy current() { return new SpendingPolicy(List.of("ACCEPTED"), false, false); }
  }
}
