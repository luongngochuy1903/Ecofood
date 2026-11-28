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
import java.util.Map;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class MetricsMongoMappingTest {
  @Test
  void propositionMonthlyActualsKeepTenantCurrencyAndNegativeProfitOnMongoRoundTrip() {
    var p = new com.ecofood.app.proposition.entity.PropositionEntity();
    p.setId(new org.bson.types.ObjectId());
    p.setActualMetricsVersion(1);
    var row = new com.ecofood.core.dto.EconomicMetricsRequest(11, 2026, bd("2"), bd("-5"),
        bd("0"), bd("50"), null, bd("100"), "partner", "USD");
    p.setActualMetrics(List.of(row));
    var bson = new Document();
    converter().write(p, bson);
    var restored = converter().read(com.ecofood.app.proposition.entity.PropositionEntity.class, bson);
    assertEquals(List.of(row), restored.getActualMetrics());
    assertEquals(1, restored.getActualMetricsVersion());
  }
  private MappingMongoConverter converter() {
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
  void signedMoneyAndPartialMetadataRoundTripAsDecimal128WithoutDatabase() {
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
  void startupDoesNotCreateStandaloneActualMetricsCollectionOrIndexes() throws Exception {
    var mongo = mock(MongoTemplate.class);
    var metrics = mock(IndexOperations.class);
    var submissions = mock(IndexOperations.class);
    var invoices = mock(IndexOperations.class);
    when(mongo.indexOps(Submission.class)).thenReturn(submissions);
    when(mongo.indexOps(Invoice.class)).thenReturn(invoices);
    new ActualMetricsMongoConfig().actualMetricsIndexes(mongo).run(null);
    verify(mongo, never()).indexOps(ActualMetrics.class);
    verifyNoInteractions(metrics);
  }
  @Test
  void sharedBatchEnvelopeAndMultiContributionMarkerRoundTripWithoutParallelCollection() {
    var converter=converter(); var now=Instant.parse("2026-11-20T03:00:00Z");
    var result=new com.ecofood.app.transaction.dto.BatchTransactionDtos.ReceiptResult(
        List.of(new com.ecofood.app.transaction.dto.BatchTransactionDtos.Item("plan",2,bd("50"))),"ACCEPTED",null);
    var batch=com.ecofood.app.submission.entity.InvoiceBatchSubmission.create("batch",now,"tenant",List.of("i1","i2"),"hash","seller",List.of(result));
    var bson=new Document(); converter.write(batch,bson);
    assertEquals(batch,converter.read(com.ecofood.app.submission.entity.InvoiceBatchSubmission.class,bson));
    assertEquals(batch.envelope(),converter.read(Submission.class,bson));
    var calculator=new KpiCalculator();
    var entries=List.of(calculator.calculate(invoice("i1","tenant","p1","USD","100")),calculator.calculate(invoice("i2","tenant","p2","USD",null)));
    var state=new SubmissionMetricsState.Processing("PARTIAL",null,now,1,1,null,null,entries);
    bson.put("actualMetrics",converter.convertToMongoType(state));
    assertEquals(state,converter.read(SubmissionMetricsState.class,bson).actualMetrics());
    var invoice=invoice("i1","tenant","p1","USD",null);
    invoice.setPlanLines(List.of(new com.ecofood.app.transaction.entity.InvoicePlanLine("1","orch","plan","Plan",2,bd("50"),bd("100"),invoice.getSnapshot(),false,Map.of("note","gift"))));
    var row=new Document(); converter.write(invoice,row);
    var line=row.getList("planLines",Document.class).get(0);
    assertInstanceOf(Decimal128.class,line.get("unitPrice"));assertInstanceOf(Decimal128.class,line.get("amount"));
    assertEquals(invoice.getPlanLines(),converter.read(Invoice.class,row).getPlanLines());
  }
}
