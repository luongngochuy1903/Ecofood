package com.ecofood.app.customer.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.customer.auth.CustomerIdentityService.CustomerSession;
import com.ecofood.app.customer.claim.CustomerInvoiceLink;
import com.ecofood.app.customer.dto.CustomerPurchaseFilter;
import com.ecofood.core.enums.TransactionStatus;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;

class CustomerPurchaseRepositoryTest {
  MongoTemplate mongo = mock(MongoTemplate.class);
  CustomerPurchaseRepository repository = new CustomerPurchaseRepository(mongo);
  CustomerSession scope = new CustomerSession("customer-a", "portal");

  @Test void ownershipIsAppliedBeforeJoinAndRequiresLiveClaimWithMatchingSellerAndPartner() {
    var stages = Aggregation.newAggregation(repository.pipeline(scope, new CustomerPurchaseFilter(null, null, null, null, null), null)).toPipeline(Aggregation.DEFAULT_CONTEXT);
    Document ownership = stages.get(0).get("$match", Document.class);
    assertEquals("customer-a", ownership.getString("customer_identifier")); assertEquals("portal", ownership.getString("customer_tenant_identifier"));
    assertEquals("CLAIMED", ownership.getString("claim_status")); assertEquals(new Document("$ne", true), ownership.get("deleted"));
    assertEquals("invoice", stages.get(2).get("$lookup", Document.class).getString("from"));
    String binding = stages.get(4).toJson();
    assertTrue(binding.contains("$invoice.tenantId")); assertTrue(binding.contains("$tenant_identifier"));
    assertTrue(binding.contains("$invoice.distributionPartnerId")); assertTrue(binding.contains("$distribution_partner_identifier"));
    assertTrue(stages.get(3).containsKey("$unwind")); // absent Invoice is dropped; never fall back to snapshot
    assertFalse(stages.toString().contains("customer_invoice_snapshot"));
  }

  @Test void detailRestrictsInvoiceAtOwnershipLookupAndDifferentCustomerChangesDatabasePredicate() {
    var filter = new CustomerPurchaseFilter(null, null, null, null, null);
    var a = Aggregation.newAggregation(repository.pipeline(scope, filter, "invoice-a")).toPipeline(Aggregation.DEFAULT_CONTEXT);
    var b = Aggregation.newAggregation(repository.pipeline(new CustomerSession("customer-b", "portal"), filter, "invoice-a")).toPipeline(Aggregation.DEFAULT_CONTEXT);
    assertEquals("invoice-a", a.get(0).get("$match", Document.class).getString("invoice_identifier"));
    assertNotEquals(a.get(0), b.get(0));
    assertTrue(a.get(1).toJson().contains("$convert")); assertTrue(a.get(1).toJson().contains("objectId"));
  }

  @Test void literalInvoiceSearchDatesPartnerAndStatusAreAppliedAfterBinding() {
    Instant now = Instant.parse("2026-11-15T00:00:00Z");
    var filter = new CustomerPurchaseFilter("INV.*", now, now.plusSeconds(60), "SHOP-A", TransactionStatus.ACCEPTED);
    var operations = repository.pipeline(scope, filter, null);
    Document values = operations.get(operations.size() - 1).toDocument(Aggregation.DEFAULT_CONTEXT).get("$match", Document.class);
    assertEquals("SHOP-A", values.getString("distributionPartnerId")); assertEquals("ACCEPTED", values.get("status"));
    assertEquals(new Document("$gte", java.util.Date.from(now)).append("$lte", java.util.Date.from(now.plusSeconds(60))), values.get("transactionTime"));
    Object expression = values.get("invoiceNumber");
    assertTrue(expression.toString().contains(Pattern.quote("INV.*"))); // caller cannot supply a regex
  }

  @Test void statisticsGroupDecimalAmountsByCurrencyAfterOwnershipAndBinding() {
    var usd = new CustomerPurchaseRepository.CurrencyAggregate(); usd.setCurrency("USD"); usd.setInvoiceCount(2); usd.setTotalAmount(new BigDecimal("0.30"));
    var vnd = new CustomerPurchaseRepository.CurrencyAggregate(); vnd.setCurrency("VND"); vnd.setInvoiceCount(1); vnd.setTotalAmount(new BigDecimal("25000"));
    var facet = new CustomerPurchaseRepository.StatisticsFacet(); facet.setTotals(List.of(usd, vnd));
    when(mongo.aggregate(any(Aggregation.class), eq("customer_invoice_link"), eq(CustomerPurchaseRepository.StatisticsFacet.class)))
        .thenReturn(new AggregationResults<>(List.of(facet), new Document()));
    var result = repository.statistics(scope, new CustomerPurchaseFilter(null, null, null, null, null));
    assertEquals(3, result.totalInvoices()); assertEquals(new BigDecimal("0.30"), result.spending().get(0).totalAmount());
    assertEquals(new BigDecimal("25000"), result.spending().get(1).totalAmount());
    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("customer_invoice_link"), eq(CustomerPurchaseRepository.StatisticsFacet.class));
    var stages = aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
    Document facets = stages.get(stages.size() - 1).get("$facet", Document.class);
    Document group = ((java.util.List<Document>) facets.get("totals")).get(0).get("$group", Document.class);
    assertEquals("$currency", group.getString("_id"));
    assertEquals(new Document("$sum", "$totalAmount"), group.get("totalAmount"));
    assertEquals("customer-a", stages.get(0).get("$match", Document.class).getString("customer_identifier"));
  }

  @Test void emptyHistoryHasZeroStatisticsWithoutSyntheticCurrencyOrMixedTotal() {
    when(mongo.aggregate(any(Aggregation.class), eq("customer_invoice_link"), eq(CustomerPurchaseRepository.StatisticsFacet.class)))
        .thenReturn(new AggregationResults<>(List.of(), new Document()));
    var result = repository.statistics(scope, new CustomerPurchaseFilter(null, null, null, null, null));
    assertEquals(0, result.totalInvoices()); assertTrue(result.spending().isEmpty());
  }
}
