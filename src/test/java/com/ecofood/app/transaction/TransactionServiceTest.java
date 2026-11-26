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
  void dashboardDetailUsesExistingTenantScopedInvoiceRead() {
    var invoice = new Invoice(); invoice.setId("invoice"); invoice.setTenantId("tenant"); invoice.setInvoiceNumber("INV-1");
    invoice.setItems(java.util.List.of(new com.ecofood.core.dto.InvoiceItemDto("P1","Product",java.math.BigDecimal.ONE,java.math.BigDecimal.TEN,java.math.BigDecimal.TEN)));
    invoice.setPlanLines(java.util.List.of(new com.ecofood.app.transaction.entity.InvoicePlanLine("line","orchestrator","plan","Plan",1,java.math.BigDecimal.TEN,java.math.BigDecimal.TEN,null,true,java.util.Map.of())));
    when(repo.findByIdAndTenantId("invoice","tenant")).thenReturn(Optional.of(invoice));
    var result = service.dashboardDetail("tenant","invoice");
    assertEquals("INV-1",result.invoice().invoiceNumber());
    assertEquals("Product",result.items().get(0).productName());
    assertEquals("Plan",result.planLines().get(0).planName());
    verify(repo).findByIdAndTenantId("invoice","tenant");
    assertThrows(ResponseStatusException.class, () -> service.dashboardDetail("other","invoice"));
  }
  @Test
  void rejectsInvalidPagination() {
    assertThrows(BadRequestException.class, () -> service.list("tenant", 0, 101));
    assertThrows(BadRequestException.class, () -> service.list("tenant", -1, 10));
    verifyNoInteractions(repo);
  }
}
