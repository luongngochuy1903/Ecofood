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
  private final com.ecofood.app.transaction.repository.DashboardInvoiceRepository dashboardInvoices;
  private final com.ecofood.core.client.CoreTenantQueryClient tenants;
  private final com.ecofood.core.client.CoreDashboardPropositionClient propositions;

  @Override
  public Page<com.ecofood.core.dto.DashboardInvoice> dashboard(java.util.Map<String, java.util.Set<String>> tenants, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid pagination");
    return dashboardPage(dashboardInvoices.find(tenants, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt", "id"))));
  }

  public Page<com.ecofood.core.dto.DashboardInvoice> dashboard(String tenant, java.util.Set<String> ids, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid pagination");
    var paging = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt", "id"));
    var result = ids == null ? invoices.findByTenantId(tenant, paging) : invoices.findByTenantIdAndIdIn(tenant, ids, paging);
    return dashboardPage(result);
  }
  private Page<com.ecofood.core.dto.DashboardInvoice> dashboardPage(Page<Invoice> page) {
    var ids = new java.util.LinkedHashSet<String>();
    page.forEach(invoice -> ids.addAll(propositionIds(invoice)));
    var names = propositionNames(ids); // One owning-module lookup for the authorized page, not one per invoice.
    return page.map(i -> new com.ecofood.core.dto.DashboardInvoice(i.getId(), i.getTenantId(), i.getPropositionId(),
        i.getTransactionTime(), i.getTotalAmount(), i.getCurrency(), i.getStatus() == null ? null : i.getStatus().name(),
        propositionReferences(propositionIds(i), names), i.getReceivedAt()));
  }
  private java.util.Set<String> propositionIds(Invoice invoice) {
    var ids = new java.util.LinkedHashSet<String>();
    if (invoice.getPropositionId() != null) ids.add(invoice.getPropositionId());
    if (invoice.getPlanLines() != null) invoice.getPlanLines().stream().map(line -> line.snapshot())
        .filter(java.util.Objects::nonNull).map(snapshot -> snapshot.propositionId())
        .filter(java.util.Objects::nonNull).forEach(ids::add);
    return ids;
  }
  private java.util.Map<String, String> propositionNames(java.util.Set<String> ids) {
    var names = new java.util.HashMap<String, String>();
    if (!ids.isEmpty()) propositions.invoiceReferences(ids).forEach(ref -> names.put(ref.id(), ref.name()));
    return names;
  }
  private java.util.List<com.ecofood.core.dto.DashboardDtos.Option> propositionReferences(
      java.util.Set<String> ids, java.util.Map<String, String> names) {
    return ids.stream().map(id -> new com.ecofood.core.dto.DashboardDtos.Option(id, names.get(id))).toList();
  }
  @Override
  public com.ecofood.core.dto.DashboardInvoiceDetail dashboardDetail(String tenant, String id) {
    var invoice = detail(tenant, id);
    var header = new com.ecofood.core.dto.DashboardInvoice(invoice.getId(), invoice.getTenantId(),
        invoice.getPropositionId(), invoice.getTransactionTime(), invoice.getTotalAmount(), invoice.getCurrency(),
        invoice.getStatus() == null ? null : invoice.getStatus().name());
    var lines = invoice.getPlanLines() == null ? java.util.List.<com.ecofood.core.dto.DashboardInvoiceDetail.PlanLine>of()
        : invoice.getPlanLines().stream().map(line -> new com.ecofood.core.dto.DashboardInvoiceDetail.PlanLine(
            line.lineId(), line.planId(), line.planName(), line.quantity(), line.unitPrice(), line.amount())).toList();
    var propositionIds = propositionIds(invoice);
    var propositionReferences = propositionReferences(propositionIds, propositionNames(propositionIds));
    return new com.ecofood.core.dto.DashboardInvoiceDetail(header, invoice.getPosId(), invoice.getSubmittedBy(),
        invoice.getDistributionPartnerId(), invoice.getPlanId(), invoice.getReceivedAt(),
        invoice.getItems() == null ? java.util.List.of() : invoice.getItems(), lines,
        tenants.tenantCode(invoice.getTenantId()), propositionReferences);
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
