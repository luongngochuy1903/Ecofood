package com.ecofood.app.customer.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.customer.auth.CustomerIdentityService.CustomerSession;
import com.ecofood.app.customer.claim.CustomerInvoiceLink;
import com.ecofood.app.customer.dto.CustomerPurchaseFilter;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;

class CustomerInsightsStatisticsTest {
  MongoTemplate mongo = mock(MongoTemplate.class);
  CustomerPurchaseRepository repository = new CustomerPurchaseRepository(mongo);
  CustomerSession scope = new CustomerSession("customer-a", "portal");
  CustomerPurchaseFilter filter = new CustomerPurchaseFilter(null, null, null, null, null);

  @Test void frequencyCountsGapsBetweenPurchaseMonthsRatherThanOnlyActiveMonths() {
    var facet = new CustomerPurchaseRepository.StatisticsFacet();
    facet.setTotals(List.of(currency("USD", 4, "0.40")));
    var frequency = new CustomerPurchaseRepository.FrequencyAggregate();
    frequency.setFirstPurchaseAt(Instant.parse("2026-01-01T00:00:00Z")); frequency.setLastPurchaseAt(Instant.parse("2026-03-31T00:00:00Z"));
    frequency.setActiveMonths(List.of("2026-01", "2026-03")); facet.setFrequency(List.of(frequency));
    var result = repository.summarize(facet, ZoneId.of("UTC"));
    assertEquals(4, result.totalOrders()); assertEquals(2, result.purchaseFrequency().activeMonths());
    assertEquals(3, result.purchaseFrequency().observedMonths()); assertEquals(new BigDecimal("1.3333"), result.purchaseFrequency().ordersPerMonth());
    assertEquals(result.spending(), result.totalSpent()); assertFalse(result.spendingPolicy().paymentStatusAvailable()); assertFalse(result.spendingPolicy().refundTrackingAvailable());
  }

  @Test void monthBoundaryUsesRequestedTimezoneAndKeepsDecimalCurrenciesSeparate() {
    var facet = new CustomerPurchaseRepository.StatisticsFacet(); facet.setTotals(List.of(currency("USD", 1, "0.1000000000001"), currency("VND", 1, "25000")));
    var frequency = new CustomerPurchaseRepository.FrequencyAggregate();
    frequency.setFirstPurchaseAt(Instant.parse("2026-01-31T18:00:00Z")); frequency.setLastPurchaseAt(Instant.parse("2026-02-01T01:00:00Z"));
    frequency.setActiveMonths(List.of("2026-02")); facet.setFrequency(List.of(frequency));
    var usd = new CustomerPurchaseRepository.MonthAggregate(); usd.setMonth("2026-02"); usd.setCurrency("USD"); usd.setOrderCount(1); usd.setTotalAmount(new BigDecimal("0.1000000000001"));
    var vnd = new CustomerPurchaseRepository.MonthAggregate(); vnd.setMonth("2026-02"); vnd.setCurrency("VND"); vnd.setOrderCount(1); vnd.setTotalAmount(new BigDecimal("25000"));
    facet.setMonthly(List.of(usd, vnd));
    var result = repository.summarize(facet, ZoneId.of("Asia/Ho_Chi_Minh"));
    assertEquals(1, result.purchaseFrequency().observedMonths()); assertEquals(new BigDecimal("2.0000"), result.purchaseFrequency().ordersPerMonth());
    assertEquals(new BigDecimal("0.1000000000001"), result.monthlySpending().get(0).totalAmount()); assertEquals("VND", result.monthlySpending().get(1).currency());
  }

  @Test void samePartnerCodeInDifferentSellerTenantsIsNeverMerged() {
    var facet = new CustomerPurchaseRepository.StatisticsFacet();
    facet.setPartners(List.of(partner("seller-a", "USD", "0.10"), partner("seller-a", "VND", "25000"), partner("seller-b", "USD", "0.20")));
    var result = repository.summarize(facet, ZoneId.of("UTC"));
    assertEquals(2, result.distributionPartners().size()); assertEquals(2, result.distributionPartners().get(0).spending().size());
    assertEquals("seller-b", result.distributionPartners().get(1).sellerTenantId()); assertEquals(new BigDecimal("0.20"), result.distributionPartners().get(1).spending().get(0).totalAmount());
  }

  @Test void recentPurchasesExposeInvoiceFieldsWithoutPosCredentialsOrSubmitter() {
    var invoice = new Invoice(); invoice.setId("invoice-id"); invoice.setInvoiceNumber("INV-1"); invoice.setDistributionPartnerId("SHOP-A");
    invoice.setCurrency("USD"); invoice.setTotalAmount(new BigDecimal("0.30")); invoice.setStatus(TransactionStatus.ACCEPTED);
    invoice.setPosId("private-pos"); invoice.setSubmittedBy("private-submitter");
    var facet = new CustomerPurchaseRepository.StatisticsFacet(); facet.setRecent(List.of(invoice));
    var recent = repository.summarize(facet, ZoneId.of("UTC")).recentPurchases().get(0);
    assertEquals("INV-1", recent.invoiceCode()); assertFalse(recent.toString().contains("private-pos")); assertFalse(recent.toString().contains("private-submitter"));
  }

  @Test void databaseFacetUsesDeduplicatedOwnershipAcceptedStatusTimezoneAndBoundedRecentList() {
    when(mongo.aggregate(any(Aggregation.class), eq("customer_invoice_link"), eq(CustomerPurchaseRepository.StatisticsFacet.class)))
        .thenReturn(new AggregationResults<>(List.of(new CustomerPurchaseRepository.StatisticsFacet()), new Document()));
    repository.statistics(scope, filter, ZoneId.of("Asia/Ho_Chi_Minh"));
    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("customer_invoice_link"), eq(CustomerPurchaseRepository.StatisticsFacet.class));
    var stages = aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
    assertEquals("customer-a", stages.get(0).get("$match", Document.class).getString("customer_identifier"));
    assertTrue(stages.stream().anyMatch(stage -> stage.containsKey("$group") && "$_id".equals(stage.get("$group", Document.class).get("_id"))));
    assertTrue(stages.stream().anyMatch(stage -> stage.containsKey("$match") && "ACCEPTED".equals(stage.get("$match", Document.class).get("status"))));
    var facets = stages.get(stages.size() - 1).get("$facet", Document.class);
    assertTrue(facets.get("monthly").toString().contains("Asia/Ho_Chi_Minh"));
    assertTrue(facets.get("monthly").toString().contains("$currency"));
    var recent = (List<Document>) facets.get("recent"); assertEquals(5L, recent.get(1).getLong("$limit"));
    assertEquals(Set.of("totals", "monthly", "recent", "partners", "frequency"), facets.keySet());
    assertFalse(stages.toString().contains("customer_invoice_snapshot"));
  }

  private CustomerPurchaseRepository.CurrencyAggregate currency(String code, long count, String total) {
    var row = new CustomerPurchaseRepository.CurrencyAggregate(); row.setCurrency(code); row.setInvoiceCount(count); row.setTotalAmount(new BigDecimal(total)); return row;
  }
  private CustomerPurchaseRepository.PartnerAggregate partner(String tenant, String currency, String amount) {
    var row = new CustomerPurchaseRepository.PartnerAggregate(); row.setSellerTenantId(tenant); row.setDistributionPartnerId("SAME-SHOP-CODE");
    row.setCurrency(currency); row.setInvoiceCount(1); row.setTotalAmount(new BigDecimal(amount)); return row;
  }
}
