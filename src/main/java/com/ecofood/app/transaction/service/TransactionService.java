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
public class TransactionService {
  private final InvoiceRepository invoices;

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
