package com.ecofood.app.transaction;

import com.ecofood.app.config.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.app.transaction.service.TransactionService;
import com.ecofood.core.dto.*;
import com.ecofood.core.service.SaleOrchestratorConfigProvider;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionServiceTest {
  InvoiceRepository repo;
  SaleOrchestratorConfigProvider provider;
  TransactionService service;
  jakarta.validation.ValidatorFactory factory;
  @BeforeEach void setup() {
    repo = mock(InvoiceRepository.class); provider = mock(SaleOrchestratorConfigProvider.class);
    ObjectProvider<SaleOrchestratorConfigProvider> beans = mock(ObjectProvider.class);
    when(beans.getIfAvailable()).thenReturn(provider);
    factory = Validation.buildDefaultValidatorFactory();
    service = new TransactionService(repo, beans, factory.getValidator());
    when(provider.findForPos("tenant", "subject")).thenReturn(Optional.of(config(true)));
    when(repo.insert(any(Invoice.class))).thenAnswer(inv -> { Invoice i = inv.getArgument(0); i.setId("id"); return i; });
  }
  @AfterEach void close() { factory.close(); }
  SaleOrchestratorConfig config(boolean active) {
    return new SaleOrchestratorConfig("tenant", "pos", "partner", "plan", "proposition", active, true, true, Set.of("VND"), 10);
  }
  SubmitTransactionRequest request(String total) {
    return new SubmitTransactionRequest("INV-1", Instant.now().minusSeconds(60), "VND", new BigDecimal(total),
        List.of(new InvoiceItemDto("P1", "Product", new BigDecimal("2"), new BigDecimal("10000"), new BigDecimal("20000"))));
  }
  @Test void persistsAtomicInvoiceWithTrustedIdentity() {
    assertEquals("id", service.submit("tenant", "subject", request("20000")).id());
    var captor = org.mockito.ArgumentCaptor.forClass(Invoice.class);
    verify(repo).insert(captor.capture());
    assertEquals("partner", captor.getValue().getDistributionPartnerId());
    assertEquals("tenant", captor.getValue().getTenantId());
    assertEquals(1, captor.getValue().getItems().size());
    assertNotNull(captor.getValue().getReceivedAt());
  }
  @Test void rejectsUnknownPosOrOtherTenant() {
    when(provider.findForPos("other", "subject")).thenReturn(Optional.empty());
    assertThrows(ForbiddenException.class, () -> service.submit("other", "subject", request("20000")));
    verifyNoInteractions(repo);
  }
  @Test void rejectsInactiveConfig() {
    when(provider.findForPos("tenant", "subject")).thenReturn(Optional.of(config(false)));
    assertThrows(IllegalStateException.class, () -> service.submit("tenant", "subject", request("20000")));
    verifyNoInteractions(repo);
  }
  @Test void rejectsBadTotals() {
    assertThrows(BadRequestException.class, () -> service.submit("tenant", "subject", request("1")));
    verifyNoInteractions(repo);
  }
  @Test void rejectsInvalidNestedItem() {
    var request = new SubmitTransactionRequest("INV", Instant.now(), "VND", BigDecimal.ZERO,
        List.of(new InvoiceItemDto("", "", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
    assertThrows(BadRequestException.class, () -> service.submit("tenant", "subject", request));
    verifyNoInteractions(repo);
  }
  @Test void rejectsDuplicate() {
    when(repo.existsByTenantIdAndPosIdAndInvoiceNumber("tenant", "pos", "INV-1")).thenReturn(true);
    assertThrows(IllegalStateException.class, () -> service.submit("tenant", "subject", request("20000")));
    verify(repo, never()).insert(any(Invoice.class));
  }
  @Test void handlesConcurrentDuplicate() {
    when(repo.insert(any(Invoice.class))).thenThrow(new DuplicateKeyException("race"));
    assertThrows(IllegalStateException.class, () -> service.submit("tenant", "subject", request("20000")));
  }
  @Test void rejectsWrongCurrency() {
    var r = request("20000");
    var wrong = new SubmitTransactionRequest(r.invoiceNumber(), r.transactionTime(), "USD", r.totalAmount(), r.items());
    assertThrows(BadRequestException.class, () -> service.submit("tenant", "subject", wrong));
    verifyNoInteractions(repo);
  }
  @Test void rejectsItemAmountMismatch() {
    var wrong = new SubmitTransactionRequest("INV", Instant.now().minusSeconds(60), "VND", BigDecimal.ONE,
        List.of(new InvoiceItemDto("P1", "Product", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE)));
    assertThrows(BadRequestException.class, () -> service.submit("tenant", "subject", wrong));
    verifyNoInteractions(repo);
  }
  @Test void isolatesDuplicateLookupByTenantAndPos() {
    service.submit("tenant", "subject", request("20000"));
    verify(repo).existsByTenantIdAndPosIdAndInvoiceNumber("tenant", "pos", "INV-1");
  }
  @Test void storageFailureDoesNotReturnSuccess() {
    when(repo.insert(any(Invoice.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
    assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
        () -> service.submit("tenant", "subject", request("20000")));
  }
}
