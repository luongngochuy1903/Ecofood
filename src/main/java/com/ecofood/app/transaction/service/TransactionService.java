package com.ecofood.app.transaction.service;

import com.ecofood.app.config.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Invoice history (list/detail) only — submitting a sale now goes through BatchTransactionService. */
@Service
@RequiredArgsConstructor
public class TransactionService implements com.ecofood.core.service.DashboardInvoiceQueryService {
  private final InvoiceRepository invoices;

  public Page<com.ecofood.core.dto.DashboardInvoice> dashboard(String tenant, java.util.Set<String> ids, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid pagination");
    var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt", "id"));
    var result = ids == null ? invoices.findByTenantId(tenant, paging) : invoices.findByTenantIdAndIdIn(tenant, ids, paging);
    return result.map(i -> new com.ecofood.core.dto.DashboardInvoice(i.getId(), i.getTenantId(), i.getInvoiceNumber(), i.getPropositionId(),
        i.getTransactionTime(), i.getTotalAmount(), i.getCurrency(), i.getStatus() == null ? null : i.getStatus().name()));
  }
  @Override
  public com.ecofood.core.dto.DashboardInvoiceDetail dashboardDetail(String tenant, String id) {
    var invoice = detail(tenant, id);
    var header = new com.ecofood.core.dto.DashboardInvoice(invoice.getId(), invoice.getTenantId(), invoice.getInvoiceNumber(),
        invoice.getPropositionId(), invoice.getTransactionTime(), invoice.getTotalAmount(), invoice.getCurrency(),
        invoice.getStatus() == null ? null : invoice.getStatus().name());
    var lines = invoice.getPlanLines() == null ? java.util.List.<com.ecofood.core.dto.DashboardInvoiceDetail.PlanLine>of()
        : invoice.getPlanLines().stream().map(line -> new com.ecofood.core.dto.DashboardInvoiceDetail.PlanLine(
            line.lineId(), line.planId(), line.planName(), line.quantity(), line.unitPrice(), line.amount())).toList();
    return new com.ecofood.core.dto.DashboardInvoiceDetail(header, invoice.getPosId(), invoice.getSubmittedBy(),
        invoice.getDistributionPartnerId(), invoice.getPlanId(), invoice.getType(), invoice.getPaymentStatus(), invoice.getReceivedAt(),
        invoice.getItems() == null ? java.util.List.of() : invoice.getItems(), lines);
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
}
