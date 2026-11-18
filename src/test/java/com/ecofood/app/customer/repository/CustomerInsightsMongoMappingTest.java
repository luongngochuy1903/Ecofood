package com.ecofood.app.customer.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.customer.auth.CustomerIdentityService.CustomerSession;
import com.ecofood.app.customer.dto.*;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.*;

/** Exercise Spring's real BSON mapping/command generation without connecting to a live database. */
class CustomerInsightsMongoMappingTest {
  List<Document> command;
  MongoTemplate template;

  @BeforeEach @SuppressWarnings("unchecked") void configureDriver() {
    var factory = mock(MongoDatabaseFactory.class);
    MongoDatabase db = mock(MongoDatabase.class);
    MongoCollection<Document> collection = mock(MongoCollection.class, RETURNS_SELF);
    AggregateIterable<Document> iterable = mock(AggregateIterable.class, RETURNS_SELF);
    MongoCursor<Document> cursor = mock(MongoCursor.class);
    when(factory.getMongoDatabase()).thenReturn(db); when(factory.getExceptionTranslator()).thenReturn(new MongoExceptionTranslator());
    when(db.getName()).thenReturn("customer-insights-test"); when(db.getCodecRegistry()).thenReturn(MongoClientSettings.getDefaultCodecRegistry());
    when(db.getCollection(anyString(), eq(Document.class))).thenReturn(collection);
    when(collection.getDocumentClass()).thenReturn(Document.class);
    when(collection.aggregate(anyList(), eq(Document.class))).thenAnswer(call -> { command = call.getArgument(0); return iterable; });
    when(collection.aggregate(anyList())).thenAnswer(call -> { command = call.getArgument(0); return iterable; });
    when(iterable.iterator()).thenReturn(cursor); when(cursor.hasNext()).thenReturn(false);
    when(iterable.into(any())).thenAnswer(call -> call.getArgument(0));
    template = new MongoTemplate(factory);
  }

  @Test void statisticsCommandKeepsInvoiceFieldsAfterReplacingLinkRoot() {
    var repository = new CustomerPurchaseRepository(template);
    repository.statistics(new CustomerSession("customer-a", "portal"), new CustomerPurchaseFilter("INV", null, null, "SHOP-A", null), ZoneId.of("Asia/Ho_Chi_Minh"));
    assertNotNull(command);
    assertTrue(command.stream().anyMatch(stage -> stage.containsKey("$match") && "SHOP-A".equals(stage.get("$match", Document.class).get("distributionPartnerId"))));
    var facet = command.get(command.size() - 1).get("$facet", Document.class);
    assertTrue(facet.get("monthly").toString().contains("Asia/Ho_Chi_Minh"));
    assertTrue(facet.get("partners").toString().contains("$tenantId"));
  }



  @Test void decimal128AndUtcDatesMapToExactNestedStatisticsWithoutFloatingPoint() {
    var raw = new Document("totals", List.of(new Document("currency", "USD").append("invoiceCount", 2L).append("totalAmount", new Decimal128(new BigDecimal("0.3000000000001")))))
        .append("monthly", List.of(new Document("month", "2026-11").append("currency", "USD").append("orderCount", 2L).append("totalAmount", new Decimal128(new BigDecimal("0.3000000000001")))))
        .append("frequency", List.of(new Document("firstPurchaseAt", Date.from(Instant.parse("2026-11-01T00:00:00Z")))
            .append("lastPurchaseAt", Date.from(Instant.parse("2026-11-15T00:00:00Z"))).append("activeMonths", List.of("2026-11"))));
    var mapped = template.getConverter().read(CustomerPurchaseRepository.StatisticsFacet.class, raw);
    var result = new CustomerPurchaseRepository(template).summarize(mapped, ZoneId.of("UTC"));
    assertEquals(new BigDecimal("0.3000000000001"), result.totalSpent().get(0).totalAmount());
    assertEquals(new BigDecimal("0.3000000000001"), result.monthlySpending().get(0).totalAmount());
    assertEquals(Instant.parse("2026-11-01T00:00:00Z"), result.purchaseFrequency().firstPurchaseAt());
  }
}
