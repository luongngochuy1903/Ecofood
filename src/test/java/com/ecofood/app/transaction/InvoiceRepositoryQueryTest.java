package com.ecofood.app.transaction;

import com.ecofood.app.transaction.dto.TransactionFilter;
import com.ecofood.app.transaction.dto.TransactionSummaryResponse.CurrencyTotal;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepositoryImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;
import org.springframework.data.mongodb.core.query.Query;
import com.ecofood.core.enums.TransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceRepositoryQueryTest {
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final InvoiceRepositoryImpl repository = new InvoiceRepositoryImpl(mongo);
  private final TransactionFilter empty = new TransactionFilter(null, null, null, null, null);

  @Test void combinesTenantIdSearchStatusAndInclusiveDatesBeforePagination() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-02-01T00:00:00Z");
    String id = "000000000000000000000001";
    var filter = new TransactionFilter(id, "INV.*", from, to, TransactionStatus.ACCEPTED);
    when(mongo.count(any(Query.class), eq(Invoice.class))).thenAnswer(call -> {
      Query query = call.getArgument(0);
      assertEquals(0, query.getSkip());
      assertEquals(0, query.getLimit());
      return 40L;
    });
    when(mongo.find(any(Query.class), eq(Invoice.class))).thenReturn(List.of());
    var result = repository.findTransactions(List.of("tenant-a"), false, filter,
        PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "transactionTime", "id")));
    var captured = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(captured.capture(), eq(Invoice.class));
    Query query = captured.getValue();
    Document conditions = query.getQueryObject();
    assertEquals(List.of("tenant-a"), conditions.get("tenantId", Document.class).get("$in"));
    assertEquals(new ObjectId(id), conditions.get("_id"));
    assertEquals(TransactionStatus.ACCEPTED, conditions.get("status"));
    Pattern search = (Pattern) conditions.get("invoiceNumber");
    assertTrue(search.matcher("prefix-INV.*-suffix").find());
    assertFalse(search.matcher("INV-123").find());
    assertEquals(from, conditions.get("transactionTime", Document.class).get("$gte"));
    assertEquals(to, conditions.get("transactionTime", Document.class).get("$lte"));
    assertEquals(20, query.getSkip());
    assertEquals(20, query.getLimit());
    assertEquals(0, query.getFieldsObject().get("items"));
    assertEquals(40, result.getTotalElements());
  }

  @Test void detailHasScopePredicateInDatabaseLookup() {
    String id = "000000000000000000000001";
    assertTrue(repository.findScopedInvoice(id, List.of("tenant-a"), false).isEmpty());
    var captured = ArgumentCaptor.forClass(Query.class);
    verify(mongo).findOne(captured.capture(), eq(Invoice.class));
    assertEquals(new ObjectId(id), captured.getValue().getQueryObject().get("_id"));
    assertEquals(List.of("tenant-a"), captured.getValue().getQueryObject()
        .get("tenantId", Document.class).get("$in"));
  }

  @Test void emptyScopeStillProducesAnEmptyInPredicate() {
    when(mongo.find(any(Query.class), eq(Invoice.class))).thenReturn(List.of());
    repository.findTransactions(List.of(), false, empty, PageRequest.of(0, 20));
    var captured = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(captured.capture(), eq(Invoice.class));
    assertEquals(List.of(), captured.getValue().getQueryObject().get("tenantId", Document.class).get("$in"));
  }

  @Test void systemScopeOmitsTenantPredicate() {
    when(mongo.find(any(Query.class), eq(Invoice.class))).thenReturn(List.of());
    repository.findTransactions(List.of(), true, empty, PageRequest.of(0, 20));
    var captured = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(captured.capture(), eq(Invoice.class));
    assertFalse(captured.getValue().getQueryObject().containsKey("tenantId"));
  }

  @Test void summaryUsesScopedFilteredMongoAggregationWithoutPaginationAndGroupsCurrencies() {
    var filter = new TransactionFilter(null, "INV", Instant.EPOCH, null, TransactionStatus.ACCEPTED);
    var rows = List.of(new CurrencyTotal("VND", 2, new BigDecimal("45000")));
    when(mongo.aggregate(any(TypedAggregation.class), eq(CurrencyTotal.class)))
        .thenReturn(new AggregationResults<>(rows, new Document()));
    assertEquals(rows, repository.summarizeTransactions(List.of("tenant-a"), false, filter));
    var captured = ArgumentCaptor.forClass(TypedAggregation.class);
    verify(mongo).aggregate(captured.capture(), eq(CurrencyTotal.class));
    List<Document> pipeline = captured.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
    Document match = pipeline.get(0).get("$match", Document.class);
    assertEquals(List.of("tenant-a"), match.get("tenantId", Document.class).get("$in"));
    assertEquals(TransactionStatus.ACCEPTED, match.get("status"));
    assertEquals(Instant.EPOCH, match.get("transactionTime", Document.class).get("$gte"));
    assertTrue(match.containsKey("invoiceNumber"));
    Document group = pipeline.get(1).get("$group", Document.class);
    assertEquals("$currency", group.get("_id"));
    assertEquals("$totalAmount", group.get("totalAmount", Document.class).get("$sum"));
    assertTrue(pipeline.stream().noneMatch(stage -> stage.containsKey("$skip") || stage.containsKey("$limit")));
  }
}
