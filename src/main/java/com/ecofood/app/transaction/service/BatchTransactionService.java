package com.ecofood.app.transaction.service;

import static com.ecofood.app.transaction.dto.BatchTransactionDtos.*;
import com.ecofood.app.config.*;
import com.ecofood.app.metrics.service.MongoMetricsTransactions;
import com.ecofood.app.orchestrator.service.BatchSalePlanResolver;
import com.ecofood.app.notification.service.SaleConfirmationHandoff;
import com.ecofood.app.submission.entity.InvoiceBatchSubmission;
import com.ecofood.app.transaction.entity.*;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.service.PropositionService;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
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
  private final PropositionService propositions;
  private final Validator validator;

  public Response submit(String tenant, String subject, Request request) {
    validateRequest(tenant, subject, request);
    // No caller-supplied invoice id exists to key a retry off of, so identity
    // IS content: the same tenant+subject submitting the exact same batch
    // (same Receipts, same order) is the one case treated as an idempotent
    // replay rather than a brand-new submission — see fingerprint below.
    String fingerprint = hash(request.transaction().invoices().stream().map(this::canonical).toList());
    String submissionId = "sale-batch:" + hash(List.of(tenant, subject, fingerprint));
    // Populated only by a FRESH batch (persistBatch returns early, before
    // touching it, on an idempotent replay) — so recordActualMetrics below
    // naturally never double-counts a retried submit.
    var created = new ArrayList<Invoice>();
    var batch = transactions.execute(ops ->
        persistBatch(ops, tenant, subject, request, submissionId, fingerprint, created));
    if (!batch.invoiceIds().isEmpty()) confirmations.deliver(batch);
    recordActualMetrics(created);
    return new Response(new Result(batch.results()));
  }

  /**
   * Accumulates each accepted Invoice's own sale amount into its
   * Proposition's actualMetrics (see PropositionService.recordActualSale)
   * — a denormalized reporting figure, not the source of truth (the
   * Invoice itself, already committed, is), so a failure here is logged
   * and swallowed rather than failing the submit-transaction response.
   */
  private void recordActualMetrics(List<Invoice> invoices) {
    for (var invoice : invoices) {
      if (invoice.getPlanLines() == null) continue;
      for (var line : invoice.getPlanLines()) {
        try {
          recordLineMetrics(invoice, line);
        } catch (RuntimeException ignored) {
          // Best-effort; see method doc.
        }
      }
    }
  }

  private void recordLineMetrics(Invoice invoice, InvoicePlanLine line) {
    var snapshot = line.snapshot();
    String propositionId = snapshot == null ? null : snapshot.propositionId();
    if (propositionId == null) return;
    var costing = snapshot.costing();
    var profitShare = snapshot.profitShare();
    BigDecimal quantity = BigDecimal.valueOf(line.quantity());
    BigDecimal ingredientCost = costing == null || costing.ingredientCost() == null
        ? BigDecimal.ZERO : costing.ingredientCost().multiply(quantity);
    BigDecimal totalCost = costing == null ? BigDecimal.ZERO : Stream.of(
            costing.ingredientCost(), costing.packagingCost(), costing.laborCost(),
            costing.operationCost(), costing.marketingFee())
        .filter(Objects::nonNull).map(c -> c.multiply(quantity)).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal platformRate = profitShare == null || profitShare.platformProfitShare() == null
        ? BigDecimal.ZERO : profitShare.platformProfitShare();
    BigDecimal platformCost = line.amount().multiply(platformRate)
        .divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
    BigDecimal profit = line.amount().subtract(totalCost).subtract(platformCost);
    propositions.recordActualSale(
        propositionId, invoice.getReceivedAt(), line.amount(), ingredientCost, platformCost, profit);
  }

  private void validateRequest(String tenant, String subject, Request request) {
    if (tenant == null || tenant.isBlank() || subject == null || subject.isBlank()) {
      throw new ForbiddenException("Verified Tenant and caller required");
    }
    if (request == null || !validator.validate(request).isEmpty()) {
      throw new BadRequestException("Invalid transaction batch");
    }
    if (request.transaction().invoices().stream().mapToInt(r -> r.items().size()).sum() > 1000) {
      throw new BadRequestException("Batch exceeds 1000 Plan lines");
    }
  }

  private InvoiceBatchSubmission persistBatch(MongoOperations ops, String tenant, String subject,
      Request request, String submissionId, String fingerprint, List<Invoice> created) {
    // The underlying Mongo transaction can retry this whole lambda body
    // (TransientTransactionError, or a duplicate-key race) — reset `created`
    // on every attempt so a retry can never leave it holding more than one
    // attempt's worth of Invoices.
    created.clear();
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
    created.addAll(invoices);
    return event;
  }

  private record PreparedReceipt(Invoice invoice, ReceiptResult result) {}

  private PreparedReceipt prepareReceipt(MongoOperations ops, String tenant, String subject,
      Receipt receipt, String submissionId, Instant now) {
    try {
      var invoice = prepare(tenant, subject, receipt, now);
      invoice.setSubmissionId(submissionId);
      return new PreparedReceipt(invoice, new ReceiptResult(receipt.items(), "ACCEPTED", null));
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
    return new PreparedReceipt(null, new ReceiptResult(receipt.items(), "REJECTED", error));
  }

  private Invoice prepare(String tenant, String subject, Receipt receipt, Instant now) {
    var lines = resolveLines(tenant, subject, receipt, now);
    var invoice = new Invoice();
    invoice.setId(new ObjectId().toHexString());
    invoice.setTenantId(tenant);
    invoice.setSubmittedBy(subject);
    invoice.setPosId(subject);
    invoice.setDistributionPartnerId(tenant);
    // No caller-supplied invoice id exists here — the Sale Orchestrator
    // never defines one. The Invoice's own generated id doubles as its
    // invoiceNumber so the pre-existing (tenantId, posId, invoiceNumber)
    // unique index and every other consumer of Invoice.invoiceNumber
    // (customer receipts, complaints, notifications) keep working unchanged.
    invoice.setInvoiceNumber(invoice.getId());
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
    // The Receipt's invoiceHolder values (whichever keys the owning Sale
    // Orchestrator(s) configured) were only validated for required-field
    // presence so far — orchestratorPayload is the internal, POS-only slot
    // that already hides from the Customer-facing receipt view, so it's
    // where they're actually kept rather than discarded after validation.
    invoice.setOrchestratorPayload(receipt.invoiceHolder());
    invoice.setPostWorkflowResults(Map.of());
    return invoice;
  }

  private List<InvoicePlanLine> resolveLines(String tenant, String subject, Receipt receipt, Instant now) {
    var lines = new ArrayList<InvoicePlanLine>();
    String currency = null;
    var items = receipt.items();
    for (int index = 0; index < items.size(); index++) {
      var line = plans.resolve(tenant, subject, index, items.get(index), now);
      String lineCurrency = line.snapshot().currency();
      if (currency != null && !currency.equals(lineCurrency)) {
        throw new BadRequestException("Invoice cannot mix currencies");
      }
      currency = lineCurrency;
      lines.add(line);
    }
    // invoiceHolder is carried once per Receipt, not per item — validated
    // once per distinct Orchestrator the Receipt's own items resolved to.
    lines.stream().map(InvoicePlanLine::orchestratorId).distinct()
        .forEach(orchestratorId -> plans.requireInvoiceHolder(orchestratorId, receipt.invoiceHolder()));
    return lines;
  }

  private Receipt canonical(Receipt receipt) {
    return new Receipt(
        receipt.invoiceHolder() == null ? null : new TreeMap<>(receipt.invoiceHolder()),
        receipt.items().stream()
            .sorted(Comparator.comparing(Item::planId))
            .map(i -> new Item(i.planId(), i.quantity(),
                i.actualPrice() == null ? null : i.actualPrice().stripTrailingZeros(),
                i.additionalInfo() == null ? null : new TreeMap<>(i.additionalInfo()))).toList());
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT,
        "Batch id already used with different content");
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
