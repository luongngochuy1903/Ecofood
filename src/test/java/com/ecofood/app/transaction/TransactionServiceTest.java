package com.ecofood.app.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.app.transaction.service.TransactionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TransactionServiceTest {
  private final InvoiceRepository repo = mock(InvoiceRepository.class);
  private final TransactionService service = new TransactionService(repo);

  @Test
  void invoiceReadIsTenantScoped() {
    when(repo.findByIdAndTenantId("invoice", "other")).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> service.detail("other", "invoice"));
    verify(repo).findByIdAndTenantId("invoice", "other");
  }

  @Test
  void invoiceDetailReturnsWhenFound() {
    var invoice = new Invoice();
    invoice.setId("invoice");
    invoice.setTenantId("tenant");
    when(repo.findByIdAndTenantId("invoice", "tenant")).thenReturn(Optional.of(invoice));
    assertSame(invoice, service.detail("tenant", "invoice"));
  }

  @Test
  void rejectsInvalidPagination() {
    assertThrows(BadRequestException.class, () -> service.list("tenant", 0, 101));
    assertThrows(BadRequestException.class, () -> service.list("tenant", -1, 10));
    verifyNoInteractions(repo);
  }
}
