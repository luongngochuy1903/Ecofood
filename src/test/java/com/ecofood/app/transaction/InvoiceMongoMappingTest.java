package com.ecofood.app.transaction;

import static org.junit.jupiter.api.Assertions.*;

import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.entity.InvoiceSnapshot;
import com.ecofood.core.enums.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.repository.query.parser.PartTree;

class InvoiceMongoMappingTest {
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

  private InvoiceSnapshot snapshot(Instant receivedAt) {
    var amount = new BigDecimal("12.3456789");
    return new InvoiceSnapshot(1, "prop", 8L, "tenant", "tenant", "plan", 3, "orch", 4,
        "product", "process", 2, 6L,
        new InvoiceSnapshot.Costing("attribute", "process", 6L, "VND", amount, amount,
            amount, amount, amount, amount, amount, amount, amount, amount),
        new InvoiceSnapshot.ProfitShare(new BigDecimal("60"), new BigDecimal("10"), new BigDecimal("30")),
        new InvoiceSnapshot.Market("VND", amount, amount, amount, amount, amount),
        "VND", receivedAt, "Asia/Ho_Chi_Minh", 11, 2026, "MISSING_DATA",
        List.of("NET_REVENUE_UNVERIFIED"), null, null, null);
  }

  @Test
  void storesExactDecimalSnapshotAndDurableHandoffOnTheSameInvoiceWithoutDatabase() throws Exception {
    var converter = converter();
    var receivedAt = Instant.parse("2026-11-20T00:00:00Z");
    var event = new Submission("invoice:INVOICE_PAID", "INVOICE_PAID", receivedAt,
        "tenant", "INVOICE", "invoice", "prop", "plan", "orch", 4);
    var invoice = new Invoice();
    invoice.setId("invoice");
    invoice.setInvoiceNumber("INV-0001-A");
    invoice.setTenantId("tenant");
    invoice.setStatus(TransactionStatus.ACCEPTED);
    invoice.setPaymentStatus("PAID");
    invoice.setType("PLAN_SALE");
    invoice.setReceivedAt(receivedAt);
    invoice.setSubmissionId(event.id());
    invoice.setSubmissionHandoff(event);
    invoice.setSubmissionPending(true);
    invoice.setSubmissionRetryAt(receivedAt);
    invoice.setSnapshot(snapshot(receivedAt));
    var bson = new Document();
    converter.write(invoice, bson);
    var storedSnapshot = bson.get("snapshot", Document.class);
    var cost = storedSnapshot.get("costing", Document.class);
    for (String field : List.of("ingredientCost", "packagingCost", "laborCost", "operationCost",
        "marketingFee", "targetMargin", "tax", "totalCost", "basePrice", "sellingPrice")) {
      assertInstanceOf(Decimal128.class, cost.get(field), field);
      assertEquals(new BigDecimal("12.3456789"), ((Decimal128) cost.get(field)).bigDecimalValue());
    }
    assertInstanceOf(Decimal128.class, storedSnapshot.get("profitShare", Document.class).get("platformProfitShare"));
    assertInstanceOf(Decimal128.class, storedSnapshot.get("market", Document.class).get("taxAmount"));
    var copy = converter.read(Invoice.class, bson);
    assertEquals(invoice.getSnapshot(), copy.getSnapshot());
    assertEquals(event, copy.getSubmissionHandoff());
    assertTrue(copy.isSubmissionPending());
    assertEquals("PAID", copy.getPaymentStatus());
    assertEquals("INV-0001-A", copy.getInvoiceNumber());
    assertNull(copy.getSnapshot().netRevenue());
    assertNull(copy.getSnapshot().profit());
    var eventBson = new Document();
    converter.write(event, eventBson);
    assertEquals(event, converter.read(Submission.class, eventBson));

    var json = new ObjectMapper().registerModule(new JavaTimeModule()).valueToTree(copy);
    assertFalse(json.has("submissionHandoff"));
    assertFalse(json.has("submissionPending"));
    assertFalse(json.has("submissionRetryAt"));
    assertFalse(json.has("submissionAttempts"));
    assertEquals(event.id(), json.get("submissionId").asText());
  }

  @Test
  void oldInvoiceRemainsReadableWithoutInventingPaidStatusSnapshotOrCustomer() throws Exception {
    var copy = converter().read(Invoice.class,
        new Document("_id", "legacy").append("invoiceNumber", "0001")
            .append("status", "ACCEPTED").append("tenantId", "tenant"));
    assertEquals("0001", copy.getInvoiceNumber());
    assertEquals(TransactionStatus.ACCEPTED, copy.getStatus());
    assertNull(copy.getPaymentStatus());
    assertNull(copy.getSnapshot());
    assertNull(copy.getInvoiceHolder());
    assertNull(copy.getSubmissionId());
    assertFalse(copy.isSubmissionPending());
    assertEquals(List.of("submissionPending", "submissionRetryAt"),
        new PartTree("findBySubmissionPendingTrueAndSubmissionRetryAtLessThanEqual", Invoice.class)
            .getParts().stream().map(part -> part.getProperty().toDotPath()).toList());
  }
}
