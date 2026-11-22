package com.ecofood.app.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.app.transaction.service.TransactionService;
import com.ecofood.app.transaction.service.InvoiceSnapshotFactory;
import com.ecofood.app.submission.service.InvoiceSubmissionHandoff;
import com.ecofood.core.dto.*;
import com.ecofood.core.service.SaleOrchestratorRuntime;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

class TransactionServiceTest {
  private final SaleOrchestratorRuntime runtime = mock(SaleOrchestratorRuntime.class);
  private final InvoiceRepository repo = mock(InvoiceRepository.class);
  private final InvoiceSnapshotFactory snapshots = mock(InvoiceSnapshotFactory.class);
  private final InvoiceSubmissionHandoff handoff = mock(InvoiceSubmissionHandoff.class);
  private final jakarta.validation.ValidatorFactory factory =
      Validation.buildDefaultValidatorFactory();
  private final TransactionService service =
      new TransactionService(runtime, repo, factory.getValidator(), snapshots, handoff);
  private SubmitTransactionRequest request;

  @BeforeEach
  void setup() {
    request =
        new SubmitTransactionRequest(
            "INV1",
            Instant.now().minusSeconds(60),
            "VND",
            new BigDecimal("20"),
            List.of(
                new InvoiceItemDto(
                    "P1", "Milk", new BigDecimal("2"), BigDecimal.TEN, new BigDecimal("20"))));
    prepare("tenant", "subject", request);
    when(repo.insert(any(Invoice.class)))
        .thenAnswer(
            inv -> {
              return inv.getArgument(0);
            });
  }

  @AfterEach
  void close() {
    factory.close();
  }

  private void prepare(String tenant, String subject, SubmitTransactionRequest r) {
    var binding =
        new SaleOrchestratorConfig(
            tenant, subject, tenant, "plan", "prop", true, true, true, Set.of("VND"), 100);
    when(runtime.prepare(eq("tenant"), eq("subject"), eq("orch"), anyMap()))
        .thenReturn(
            new PreparedSaleTransaction(
                "orch",
                2,
                binding,
                r,
                Map.of("custom", "value"),
                Set.of("SEND_EMAIL_NOTIFICATION")));
  }

  @Test
  void savesInvoiceWithTrustedScopeVersionAndWorkflowInOneInsert() {
    var response = service.submit("tenant", "subject", "orch", Map.of());
    var capture = org.mockito.ArgumentCaptor.forClass(Invoice.class);
    verify(repo).insert(capture.capture());
    var invoice = capture.getValue();
    assertEquals(invoice.getId(), response.id());
    assertTrue(org.bson.types.ObjectId.isValid(invoice.getId()));
    assertEquals(com.ecofood.core.enums.TransactionStatus.ACCEPTED, invoice.getStatus());
    assertEquals("PAID", invoice.getPaymentStatus());
    assertEquals("PLAN_SALE", invoice.getType());
    assertNull(invoice.getInvoiceHolder());
    assertNull(invoice.getInvoiceDocumentId());
    assertEquals("tenant", invoice.getTenantId());
    assertEquals("subject", invoice.getSubmittedBy());
    assertEquals("plan", invoice.getPlanId());
    assertEquals(2, invoice.getOrchestratorVersion());
    assertEquals("orch", invoice.getOrchestratorId());
    assertEquals(Map.of("SEND_EMAIL_NOTIFICATION", "PENDING"), invoice.getPostWorkflowResults());
    assertEquals("value", invoice.getOrchestratorPayload().get("custom"));
    assertNotNull(invoice.getReceivedAt());
    var order = inOrder(handoff, repo);
    order.verify(handoff).prepare(invoice);
    order.verify(repo).insert(invoice);
    order.verify(handoff).deliver(invoice);
  }

  @Test
  void rejectsTenantOrCallerMismatchBeforePersisting() {
    prepare("other", "subject", request);
    assertThrows(
        ForbiddenException.class, () -> service.submit("tenant", "subject", "orch", Map.of()));
    prepare("tenant", "other", request);
    assertThrows(
        ForbiddenException.class, () -> service.submit("tenant", "subject", "orch", Map.of()));
    verifyNoInteractions(repo);
  }

  @Test
  void rejectsIncorrectInvoiceTotal() {
    prepare(
        "tenant",
        "subject",
        new SubmitTransactionRequest(
            request.invoiceNumber(),
            request.transactionTime(),
            request.currency(),
            BigDecimal.ONE,
            request.items()));
    assertThrows(
        BadRequestException.class, () -> service.submit("tenant", "subject", "orch", Map.of()));
    verifyNoInteractions(repo);
  }

  @Test
  void rejectsIncorrectLineAmount() {
    prepare(
        "tenant",
        "subject",
        new SubmitTransactionRequest(
            "INV1",
            request.transactionTime(),
            "VND",
            BigDecimal.ONE,
            List.of(
                new InvoiceItemDto("P", "Milk", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE))));
    assertThrows(
        BadRequestException.class, () -> service.submit("tenant", "subject", "orch", Map.of()));
    verifyNoInteractions(repo);
  }

  @Test
  void rejectsInvalidDtoAndCurrency() {
    prepare(
        "tenant",
        "subject",
        new SubmitTransactionRequest(
            "", request.transactionTime(), "VND", request.totalAmount(), request.items()));
    assertThrows(
        BadRequestException.class, () -> service.submit("tenant", "subject", "orch", Map.of()));
    prepare(
        "tenant",
        "subject",
        new SubmitTransactionRequest(
            "INV1", request.transactionTime(), "USD", request.totalAmount(), request.items()));
    assertThrows(
        BadRequestException.class, () -> service.submit("tenant", "subject", "orch", Map.of()));
    verifyNoInteractions(repo);
  }

  @Test
  void rejectsDuplicateAcrossVersions() {
    when(repo.existsByTenantIdAndPosIdAndInvoiceNumber("tenant", "subject", "INV1"))
        .thenReturn(true);
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () -> service.submit("tenant", "subject", "orch", Map.of()))
            .getStatusCode()
            .value());
    verify(repo, never()).insert(any(Invoice.class));
    verifyNoInteractions(handoff);
  }

  @Test
  void concurrentDuplicateUsesSame409Contract() {
    doThrow(new DuplicateKeyException("race")).when(repo).insert(any(Invoice.class));
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () -> service.submit("tenant", "subject", "orch", Map.of()))
            .getStatusCode()
            .value());
    verify(handoff, never()).deliver(any());
  }

  @Test
  void databaseFailureDoesNotReturnAccepted() {
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"))
        .when(repo)
        .insert(any(Invoice.class));
    assertThrows(
        org.springframework.dao.DataAccessResourceFailureException.class,
        () -> service.submit("tenant", "subject", "orch", Map.of()));
    verify(handoff, never()).deliver(any());
  }

  @Test
  void invoiceReadIsTenantScoped() {
    when(repo.findByIdAndTenantId("invoice", "other")).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> service.detail("other", "invoice"));
    verify(repo).findByIdAndTenantId("invoice", "other");
    assertThrows(BadRequestException.class, () -> service.list("tenant", 0, 101));
  }

  @Test
  void storesTheCapturedSnapshotAndDoesNotAcceptSnapshotIdentityFromPayload() {
    var snapshot = mock(com.ecofood.app.transaction.entity.InvoiceSnapshot.class);
    when(snapshots.capture(eq("tenant"), any(PreparedSaleTransaction.class), any(Instant.class)))
        .thenReturn(snapshot);
    service.submit("tenant", "subject", "orch", Map.of("tenantId", "other"));
    var capture = org.mockito.ArgumentCaptor.forClass(Invoice.class);
    verify(repo).insert(capture.capture());
    assertSame(snapshot, capture.getValue().getSnapshot());
    assertEquals("tenant", capture.getValue().getTenantId());
  }
}
