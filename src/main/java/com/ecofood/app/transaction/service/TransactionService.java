package com.ecofood.app.transaction.service;

import com.ecofood.app.config.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.service.SaleOrchestratorRuntime;
import com.ecofood.app.submission.service.InvoiceSubmissionHandoff;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TransactionService {
  private final SaleOrchestratorRuntime runtime;
  private final InvoiceRepository invoices;
  private final Validator validator;
  private final InvoiceSnapshotFactory snapshots;
  private final InvoiceSubmissionHandoff handoff;

  public SubmitTransactionResponse submit(
      String tenant, String subject, String orchestrator, Map<String, Object> payload) {
    if (blank(tenant) || blank(subject))
      throw new ForbiddenException("Verified Tenant and caller identity required");
    var prepared = runtime.prepare(tenant, subject, orchestrator, payload);
    validateBinding(tenant, subject, orchestrator, prepared);
    var request = prepared.transaction();
    validateRequest(prepared.binding(), request);
    validateTotals(request);
    if (invoices.existsByTenantIdAndPosIdAndInvoiceNumber(tenant, subject, request.invoiceNumber()))
      throw duplicate();
    var invoice = createInvoice(tenant, subject, prepared);
    handoff.prepare(invoice);
    try {
      invoice = invoices.insert(invoice);
    } catch (DuplicateKeyException ex) {
      throw duplicate();
    }
    handoff.deliver(invoice);
    return new SubmitTransactionResponse(
        invoice.getId(), invoice.getInvoiceNumber(), invoice.getStatus(), invoice.getReceivedAt());
  }

  private void validateBinding(String tenant, String subject, String orchestrator,
      PreparedSaleTransaction prepared) {
    var binding = prepared.binding();
    if (!tenant.equals(binding.tenantId())
        || !subject.equals(binding.posId())
        || !Objects.equals(orchestrator, prepared.orchestratorId()))
      throw new ForbiddenException("Invalid Sale binding");
    if (!binding.active()
        || !binding.planActive()
        || !binding.propositionActive()
        || blank(binding.planId())
        || blank(binding.propositionId())
        || blank(binding.distributionPartnerId())
        || prepared.orchestratorVersion() < 1)
      throw new IllegalStateException("Sale configuration, Plan or Proposition unavailable");
  }

  private void validateRequest(SaleOrchestratorConfig binding, SubmitTransactionRequest request) {
    if (request == null || !validator.validate(request).isEmpty())
      throw new BadRequestException("Invalid transaction payload");
    if (binding.currencies() == null
        || !binding.currencies().contains(request.currency())
        || request.items().size() > binding.maxItems())
      throw new BadRequestException("Payload violates Plan currency or item limit");
  }

  private void validateTotals(SubmitTransactionRequest request) {
    BigDecimal total = BigDecimal.ZERO;
    for (var item : request.items()) {
      if (item.quantity().multiply(item.unitPrice()).compareTo(item.amount()) != 0)
        throw new BadRequestException("Item amount must equal quantity * unitPrice");
      total = total.add(item.amount());
    }
    if (total.compareTo(request.totalAmount()) != 0)
      throw new BadRequestException("Invoice total does not match items");
  }

  private Invoice createInvoice(String tenant, String subject, PreparedSaleTransaction prepared) {
    var binding = prepared.binding();
    var request = prepared.transaction();
    var invoice = new Invoice();
    invoice.setId(new ObjectId().toHexString());
    invoice.setTenantId(tenant);
    invoice.setSubmittedBy(subject);
    invoice.setPosId(subject);
    invoice.setDistributionPartnerId(binding.distributionPartnerId());
    invoice.setPlanId(binding.planId());
    invoice.setPropositionId(binding.propositionId());
    invoice.setOrchestratorId(prepared.orchestratorId());
    invoice.setOrchestratorVersion(prepared.orchestratorVersion());
    invoice.setInvoiceNumber(request.invoiceNumber());
    invoice.setCurrency(request.currency());
    invoice.setTransactionTime(request.transactionTime());
    // MongoDB dates have millisecond precision; keep the durable event identical across retries.
    invoice.setReceivedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    invoice.setStatus(TransactionStatus.ACCEPTED);
    invoice.setType("PLAN_SALE");
    invoice.setPaymentStatus("PAID");
    invoice.setSnapshot(snapshots.capture(tenant, prepared, invoice.getReceivedAt()));
    invoice.setTotalAmount(request.totalAmount());
    invoice.setItems(List.copyOf(request.items()));
    invoice.setOrchestratorPayload(prepared.payload());
    invoice.setPostWorkflowResults(postWorkflows(prepared.postWorkflows()));
    return invoice;
  }

  private Map<String, String> postWorkflows(Set<String> actions) {
    var workflows = new LinkedHashMap<String, String>();
    if (actions == null) return workflows;
    for (String action : actions) {
      if (!Set.of("CREATE_OR_LINK_CUSTOMER", "SEND_EMAIL_NOTIFICATION").contains(action)) {
        throw new BadRequestException("Unsupported post-workflow");
      }
      workflows.put(action, "PENDING");
    }
    return workflows;
  }

  public Invoice detail(String tenant, String id) {
    return invoices
        .findByIdAndTenantId(id, tenant)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
  }

  public Page<Invoice> list(String tenant, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid pagination");
    return invoices.findByTenantId(
        tenant, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt", "id")));
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private ResponseStatusException duplicate() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate transaction");
  }
}
