package com.ecofood.app.metrics;

import static com.ecofood.app.metrics.MetricsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.metrics.config.ActualMetricsMongoConfig;
import com.ecofood.app.metrics.entity.*;
import com.ecofood.app.metrics.service.KpiCalculator;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class MetricsMongoMappingTest {
  private MappingMongoConverter converter() throws Exception {
    var conversions = new com.ecofood.app.proposition.service.PropositionMongoConfiguration()
        .propositionMongoConversions();
    var context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
    return converter;
  }

  @Test
  void signedMoneyAndPartialMetadataRoundTripAsDecimal128WithoutDatabase() throws Exception {
    var converter = converter();
    var calculator = new KpiCalculator();
    var invoice = invoice("i", "tenant", "prop", "USD", "1");
    var contribution = calculator.calculate(invoice);
    var now = Instant.parse("2026-11-20T03:00:00Z");
    var row = ActualMetrics.empty(contribution.key(), KpiCalculator.VERSION, now).add(contribution, now);
    var bson = new Document();
    converter.write(row, bson);
    for (var field : List.of("ingredientCost", "platformCost", "profit"))
      assertInstanceOf(Decimal128.class, bson.get(field));
    assertEquals(bd("-23.10"), bson.get("profit", Decimal128.class).bigDecimalValue());
    assertEquals(row, converter.read(ActualMetrics.class, bson));
    var partial = calculator.calculate(invoice("i2", "tenant", "prop", "USD", null));
    var incomplete = row.add(partial, now);
    var partialBson = new Document();
    converter.write(incomplete, partialBson);
    var copy = converter.read(ActualMetrics.class, partialBson);
    assertNull(copy.profit());
    assertNull(copy.platformCost());
    assertEquals(1, copy.coverage().profitInvoices());
    assertEquals(bd("-23.10"), copy.knownTotals().profit());

    // Processing metadata does not change the Task 3A envelope equality used for handoff dedup.
    var eventBson = new Document();
    converter.write(invoice.getSubmissionHandoff(), eventBson);
    var state = new SubmissionMetricsState.Processing("SUCCEEDED", contribution, now, 1, 1, null, null);
    eventBson.put("actualMetrics", converter.convertToMongoType(state));
    assertEquals(invoice.getSubmissionHandoff(), converter.read(Submission.class, eventBson));
    var stateCopy = converter.read(SubmissionMetricsState.class, eventBson);
    assertEquals(state, stateCopy.actualMetrics());
  }

  @Test
  void uniqueIndexExactlyMatchesTenantMonthlyCurrencyBusinessGrain() throws Exception {
    var mongo = mock(MongoTemplate.class);
    var metrics = mock(IndexOperations.class);
    var submissions = mock(IndexOperations.class);
    var invoices = mock(IndexOperations.class);
    when(mongo.indexOps(ActualMetrics.class)).thenReturn(metrics);
    when(mongo.indexOps(Submission.class)).thenReturn(submissions);
    when(mongo.indexOps(Invoice.class)).thenReturn(invoices);
    new ActualMetricsMongoConfig().actualMetricsIndexes(mongo).run(null);
    var index = org.mockito.ArgumentCaptor.forClass(IndexDefinition.class);
    verify(metrics).ensureIndex(index.capture());
    assertEquals(List.of("propositionId", "tenantId", "year", "month", "currency"),
        index.getValue().getIndexKeys().keySet().stream().toList());
    assertEquals(true, index.getValue().getIndexOptions().getBoolean("unique"));
  }
}
