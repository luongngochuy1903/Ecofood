package com.ecofood.app.transaction.service;

import static com.ecofood.app.transaction.dto.BatchTransactionDtos.*;
import com.ecofood.app.config.*;
import com.ecofood.app.metrics.service.MongoMetricsTransactions;
import com.ecofood.app.orchestrator.service.BatchSalePlanResolver;
import com.ecofood.app.notification.service.SaleConfirmationHandoff;
import com.ecofood.app.submission.entity.InvoiceBatchSubmission;
import com.ecofood.app.transaction.entity.*;
import com.ecofood.core.enums.TransactionStatus;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BatchTransactionService {
  private static final String TENANT_ID = "tenantId";
  private final MongoMetricsTransactions transactions;
  private final BatchSalePlanResolver plans;
  private final SaleConfirmationHandoff confirmations;
  private final Validator validator;

  public Response submit(String tenant, String subject, Request request) {
    var numbers = validateRequest(tenant, subject, request);
    String submissionId = "sale-batch:" + hash(List.of(tenant, subject, numbers));
    String fingerprint = hash(request.transaction().invoices().stream()
        .sorted(Comparator.comparing(Receipt::invoiceNumber)).map(this::canonical).toList());
    var batch = transactions.execute(ops ->
        persistBatch(ops, tenant, subject, request, submissionId, fingerprint));
    if (!batch.invoiceIds().isEmpty()) confirmations.deliver(batch);
    return responseInRequestOrder(request, batch);
  }

  private SortedSet<String> validateRequest(String tenant, String subject, Request request) {
    if (tenant == null || tenant.isBlank() || subject == null || subject.isBlank()) {
      throw new ForbiddenException("Verified Tenant and caller required");
    }
    if (request == null || !validator.validate(request).isEmpty()) {
      throw new BadRequestException("Invalid transaction batch");
    }
    if (request.transaction().invoices().stream().mapToInt(r -> r.items().size()).sum() > 1000) {
      throw new BadRequestException("Batch exceeds 1000 Plan lines");
    }
    var numbers = new TreeSet<String>();
    for (var receipt : request.transaction().invoices()) {
      if (!numbers.add(receipt.invoiceNumber())) {
        throw new BadRequestException("Duplicate invoiceNumber in batch");
      }
    }
    return numbers;
  }

  private InvoiceBatchSubmission persistBatch(MongoOperations ops, String tenant, String subject,
      Request request, String submissionId, String fingerprint) {
    var query = Query.query(Criteria.where("_id").is(submissionId).and(TENANT_ID).is(tenant));
    var previous = ops.findOne(query, InvoiceBatchSubmission.class);
    if (previous != null) {
      if (!fingerprint.equals(previous.sourcePayloadFingerprint())) throw conflict();
      return previous;
    }
    var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var results = new ArrayList<ReceiptResult>();
    var invoices = new ArrayList<Invoice>();
    for (var receipt : request.transaction().invoices()) {
      var prepared = prepareReceipt(ops, tenant, subject, receipt, submissionId, now);
      results.add(prepared.result());
      if (prepared.invoice() != null) invoices.add(prepared.invoice());
    }
    var event = InvoiceBatchSubmission.create(submissionId, now, tenant,
        invoices.stream().map(Invoice::getId).toList(), fingerprint, subject, results);
    if (invoices.isEmpty()) return event;
    // Accepted Invoices and ONE shared event still commit in the SAME transaction.
    for (var invoice : invoices) {
      ops.insert(invoice);
    }
    ops.insert(event);
    return event;
  }

  private record PreparedReceipt(Invoice invoice, ReceiptResult result) {}

  private PreparedReceipt prepareReceipt(MongoOperations ops, String tenant, String subject,
      Receipt receipt, String submissionId, Instant now) {
    var existing = ops.findOne(Query.query(Criteria.where(TENANT_ID).is(tenant)
        .and("posId").is(subject).and("invoiceNumber").is(receipt.invoiceNumber())), Invoice.class);
    if (existing != null) {
      return rejected(receipt, "INVOICE_ALREADY_SUBMITTED",
          "Invoice belongs to an earlier Submit; do not include it in a new batch");
    }
    try {
      var invoice = prepare(tenant, subject, receipt, now);
      invoice.setSubmissionId(submissionId);
      return new PreparedReceipt(invoice,
          new ReceiptResult(receipt.invoiceNumber(), receipt.items(), "ACCEPTED", null));
    } catch (BadRequestException invalid) {
      return rejected(receipt, "INVALID_INVOICE", invalid.getMessage());
    } catch (ForbiddenException denied) {
      return rejected(receipt, "SCOPE_DENIED", "Plan or Orchestrator unavailable for this Tenant");
    } catch (ResponseStatusException failure) {
      return rejected(receipt, "INVOICE_" + failure.getStatusCode().value(), "Plan configuration unavailable");
    } catch (IllegalStateException unavailable) {
      return rejected(receipt, "CONFIGURATION_UNAVAILABLE", "Active configuration unavailable");
    }
  }

  private PreparedReceipt rejected(Receipt receipt, String code, String message) {
    var error = new com.ecofood.app.transaction.dto.BatchTransactionDtos.Error(code, message);
    return new PreparedReceipt(null,
        new ReceiptResult(receipt.invoiceNumber(), receipt.items(), "REJECTED", error));
  }

  private Response responseInRequestOrder(Request request, InvoiceBatchSubmission batch) {
    var byNumber = new HashMap<String, ReceiptResult>();
    batch.results().forEach(r -> byNumber.put(r.invoiceNumber(), r));
    var results = request.transaction().invoices().stream()
        .map(r -> byNumber.get(r.invoiceNumber())).toList();
    return new Response(new Result(results));
  }

  private Invoice prepare(String tenant, String subject, Receipt receipt, Instant now) {
    var lines = resolveLines(tenant, subject, receipt, now);
    var invoice = new Invoice();
    invoice.setId(new ObjectId().toHexString());
    invoice.setTenantId(tenant);
    invoice.setSubmittedBy(subject);
    invoice.setPosId(subject);
    invoice.setDistributionPartnerId(tenant);
    invoice.setInvoiceNumber(receipt.invoiceNumber());
    invoice.setCurrency(lines.get(0).snapshot().currency());
    invoice.setReceivedAt(now);
    invoice.setTransactionTimeSource("NOT_SUPPLIED");
    invoice.setType("PLAN_SALE_BATCH");
    invoice.setStatus(TransactionStatus.ACCEPTED);
    invoice.setPaymentStatus("PAID");
    invoice.setTotalAmount(lines.stream().map(InvoicePlanLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    invoice.setPlanLines(List.copyOf(lines));
    invoice.setSourcePayloadFingerprint(hash(canonical(receipt)));
    invoice.setItems(List.of());
    invoice.setPostWorkflowResults(Map.of());
    return invoice;
  }

  private List<InvoicePlanLine> resolveLines(String tenant, String subject, Receipt receipt, Instant now) {
    var lines = new ArrayList<InvoicePlanLine>();
    var ids = new HashSet<String>();
    String currency = null;
    for (var item : receipt.items()) {
      if (!ids.add(item.lineId())) throw new BadRequestException("Duplicate lineId in Invoice");
      if (item.unitPrice().multiply(item.quantity()).compareTo(item.amount()) != 0) {
        throw new BadRequestException("Item amount must equal quantity * unitPrice");
      }
      var line = plans.resolve(tenant, subject, receipt.invoiceNumber(), item, now);
      String lineCurrency = line.snapshot().currency();
      if (currency != null && !currency.equals(lineCurrency)) {
        throw new BadRequestException("Invoice cannot mix currencies");
      }
      currency = lineCurrency;
      lines.add(line);
    }
    return lines;
  }

  private Receipt canonical(Receipt receipt) {
    return new Receipt(receipt.invoiceNumber(), receipt.items().stream()
        .sorted(Comparator.comparing(Item::lineId))
        .map(i -> new Item(i.lineId(), i.orchestratorId(), i.planId(), i.quantity(),
            i.unitPrice().stripTrailingZeros(), i.amount().stripTrailingZeros())).toList());
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT,
        "Batch invoice numbers already used with different content");
  }

  private String hash(Object value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(value)));
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot fingerprint batch", failure);
    }
  }
}
