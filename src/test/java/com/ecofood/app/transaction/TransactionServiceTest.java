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
  private final com.ecofood.core.client.CoreTenantQueryClient tenants = mock(com.ecofood.core.client.CoreTenantQueryClient.class);
  private final com.ecofood.core.client.CoreDashboardPropositionClient propositions = mock(com.ecofood.core.client.CoreDashboardPropositionClient.class);
  private final TransactionService service = new TransactionService(repo, mock(com.ecofood.app.transaction.repository.DashboardInvoiceRepository.class), tenants, propositions);

  @Test void invoiceDetailResolvesTenantCodeAndDistinctPropositionsFromLineSnapshots() {
    var invoice = new Invoice(); invoice.setId("invoice"); invoice.setTenantId("tenant");
    var snapshot = mock(com.ecofood.app.transaction.entity.InvoiceSnapshot.class);
    when(snapshot.propositionId()).thenReturn("proposition");
    var line = new com.ecofood.app.transaction.entity.InvoicePlanLine("line","orchestrator","plan","Plan",1,java.math.BigDecimal.TEN,java.math.BigDecimal.TEN,snapshot,true,java.util.Map.of());
    invoice.setPlanLines(java.util.List.of(line,line));
    when(repo.findByIdAndTenantId("invoice","tenant")).thenReturn(Optional.of(invoice));
    when(tenants.tenantCode("tenant")).thenReturn("TE0001");
    when(propositions.invoiceReferences(java.util.Set.of("proposition"))).thenReturn(java.util.List.of(new com.ecofood.core.dto.DashboardDtos.Option("proposition","Proposition name")));
    var result = service.dashboardDetail("tenant","invoice");
    assertEquals("TE0001",result.tenantCode()); assertEquals(1,result.propositions().size());
    assertEquals("proposition",result.propositions().get(0).id());
    assertEquals("Proposition name",result.propositions().get(0).name());
  }

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
  @Test void dashboardPageResolvesAllLinePropositionsOnceAndPreservesPagination() {
    var first = new Invoice(); first.setId("first"); first.setTenantId("tenant");
    var second = new Invoice(); second.setId("second"); second.setTenantId("tenant"); second.setPropositionId("legacy");
    var snapshotA = mock(com.ecofood.app.transaction.entity.InvoiceSnapshot.class);
    var snapshotB = mock(com.ecofood.app.transaction.entity.InvoiceSnapshot.class);
    when(snapshotA.propositionId()).thenReturn("A"); when(snapshotB.propositionId()).thenReturn("B");
    var a = new com.ecofood.app.transaction.entity.InvoicePlanLine("a","orchestrator","plan-a","A",1,java.math.BigDecimal.TEN,java.math.BigDecimal.TEN,snapshotA,true,java.util.Map.of());
    var b = new com.ecofood.app.transaction.entity.InvoicePlanLine("b","orchestrator","plan-b","B",1,java.math.BigDecimal.TEN,java.math.BigDecimal.TEN,snapshotB,true,java.util.Map.of());
    first.setPlanLines(java.util.List.of(a,b,a));
    var paging = org.springframework.data.domain.PageRequest.of(1,20,org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,"receivedAt","id"));
    when(repo.findByTenantId("tenant",paging)).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(first,second),paging,42));
    when(propositions.invoiceReferences(java.util.Set.of("A","B","legacy"))).thenReturn(java.util.List.of(
        new com.ecofood.core.dto.DashboardDtos.Option("A","Proposition A"),new com.ecofood.core.dto.DashboardDtos.Option("B","Proposition B")));
    var result = service.dashboard("tenant",null,1,20);
    assertEquals(42,result.getTotalElements()); assertEquals(1,result.getNumber());
    assertEquals(java.util.List.of("A","B"),result.getContent().get(0).propositions().stream().map(com.ecofood.core.dto.DashboardDtos.Option::id).toList());
    assertEquals("Proposition A",result.getContent().get(0).propositions().get(0).name());
    assertEquals("legacy",result.getContent().get(1).propositions().get(0).id());
    verify(propositions,times(1)).invoiceReferences(java.util.Set.of("A","B","legacy"));
    verifyNoMoreInteractions(propositions);
  }
}
