package com.ecofood.app.transaction.service;

import com.ecofood.app.config.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.service.SaleOrchestratorConfigProvider;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@RequiredArgsConstructor
public class TransactionService {
  private final InvoiceRepository invoices;
  private final ObjectProvider<SaleOrchestratorConfigProvider> providers;
  private final Validator validator;

  public SubmitTransactionResponse submit(String tenantId, String subject, SubmitTransactionRequest request) {
    var provider = providers.getIfAvailable();
    if (provider == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sale Orchestrator provider unavailable");
    var config = provider.findForPos(tenantId, subject)
        .orElseThrow(() -> new ForbiddenException("POS is not registered for this tenant"));
    if (!tenantId.equals(config.tenantId()) || blank(config.posId()) || blank(config.distributionPartnerId()))
      throw new ForbiddenException("Invalid POS binding");
    if (!config.active() || !config.planActive() || !config.propositionActive())
      throw new IllegalStateException("Sale Orchestrator, Plan or Proposition is inactive");
    if (blank(config.planId()) || blank(config.propositionId()) || config.currencies() == null || config.maxItems() < 1)
      throw new IllegalStateException("Incomplete Sale Orchestrator configuration");
    if (request == null || !validator.validate(request).isEmpty()) throw new BadRequestException("Invalid transaction payload");
    if (!config.currencies().contains(request.currency()) || request.items().size() > config.maxItems())
      throw new BadRequestException("Payload violates Sale Orchestrator rules");
    BigDecimal total = BigDecimal.ZERO;
    for (var item : request.items()) {
      if (item.quantity().multiply(item.unitPrice()).compareTo(item.amount()) != 0)
        throw new BadRequestException("Item amount must equal quantity * unitPrice");
      total = total.add(item.amount());
    }
    if (total.compareTo(request.totalAmount()) != 0) throw new BadRequestException("Invoice total does not match items");
    if (invoices.existsByTenantIdAndPosIdAndInvoiceNumber(tenantId, config.posId(), request.invoiceNumber()))
      throw new IllegalStateException("Duplicate transaction");
    Invoice invoice = new Invoice();
    invoice.setTenantId(tenantId); invoice.setPosId(config.posId()); invoice.setSubmittedBy(subject);
    invoice.setDistributionPartnerId(config.distributionPartnerId());
    invoice.setPlanId(config.planId()); invoice.setPropositionId(config.propositionId());
    invoice.setInvoiceNumber(request.invoiceNumber()); invoice.setTransactionTime(request.transactionTime());
    invoice.setReceivedAt(Instant.now()); invoice.setCurrency(request.currency());
    invoice.setTotalAmount(request.totalAmount()); invoice.setItems(List.copyOf(request.items()));
    invoice.setStatus(TransactionStatus.ACCEPTED);
    try { invoice = invoices.insert(invoice); }
    catch (DuplicateKeyException ex) { throw new IllegalStateException("Duplicate transaction"); }
    return new SubmitTransactionResponse(invoice.getId(), invoice.getInvoiceNumber(), invoice.getStatus(), invoice.getReceivedAt());
  }

  private boolean blank(String value) { return value == null || value.isBlank(); }
}
