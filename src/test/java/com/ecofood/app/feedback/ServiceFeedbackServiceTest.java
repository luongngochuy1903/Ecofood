package com.ecofood.app.feedback;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.feedback.dto.*;
import com.ecofood.app.feedback.entity.*;
import com.ecofood.app.feedback.repository.ServiceFeedbackRepository;
import com.ecofood.app.feedback.service.ServiceFeedbackService;
import com.ecofood.app.receipt.service.ReceiptService;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.enums.TransactionStatus;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceFeedbackServiceTest {
  private final ReceiptService receipts = mock(ReceiptService.class);
  private final ServiceFeedbackRepository feedbacks = mock(ServiceFeedbackRepository.class);
  private ValidatorFactory factory;
  private ServiceFeedbackService service;
  private Invoice invoice;

  @BeforeEach void setup() {
    factory = Validation.buildDefaultValidatorFactory();
    service = new ServiceFeedbackService(receipts, feedbacks, factory.getValidator());
    invoice = new Invoice(); invoice.setId("invoice-a"); invoice.setTenantId("tenant-a");
    invoice.setDistributionPartnerId("partner-a"); invoice.setPosId("pos-a");
    invoice.setStatus(TransactionStatus.ACCEPTED);
    when(receipts.requireValidInvoice("token-a")).thenReturn(invoice);
  }
  @AfterEach void close() { factory.close(); }

  @Test void submitBindsTrustedInvoiceAndPartnerWithoutChangingInvoice() {
    assertTrue(service.submit("token-a", request("5", "  Helpful staff  ")).submitted());
    var captured = org.mockito.ArgumentCaptor.forClass(ServiceFeedback.class);
    verify(feedbacks).insert(captured.capture());
    var saved = captured.getValue();
    assertEquals("invoice-a", saved.getInvoiceId()); assertEquals("tenant-a", saved.getTenantId());
    assertEquals("partner-a", saved.getDistributionPartnerId()); assertEquals(5, saved.getRating());
    assertEquals("Helpful staff", saved.getComment()); assertEquals(ServiceFeedbackStatus.SUBMITTED, saved.getStatus());
    assertFalse(saved.getDeleted()); assertNull(saved.getCreatedBy());
    assertEquals(TransactionStatus.ACCEPTED, invoice.getStatus()); assertEquals("pos-a", invoice.getPosId());
    verify(receipts).requireValidInvoice("token-a");
  }

  @Test void optionalOrBlankCommentIsStoredAsNull() {
    for (String comment : new String[] { null, "", "  \n " }) service.submit("token-a", request("1", comment));
    var captured = org.mockito.ArgumentCaptor.forClass(ServiceFeedback.class);
    verify(feedbacks, times(3)).insert(captured.capture());
    assertTrue(captured.getAllValues().stream().allMatch(value -> value.getComment() == null));
  }

  @Test void rejectsMissingOutOfRangeAndFractionalRatingsAndOversizedComment() {
    for (var request : List.of(new SubmitServiceFeedbackRequest(null, null), request("0", null), request("6", null),
        request("-1", null), request("1.5", null), request("5", "x".repeat(2001)))) {
      assertThrows(BadRequestException.class, () -> service.submit("token-a", request));
    }
    assertThrows(BadRequestException.class, () -> service.submit("token-a", null));
    verifyNoInteractions(feedbacks);
  }

  @Test void acceptsCommentAtMaximumLength() {
    service.submit("token-a", request("5", "x".repeat(2000)));
    verify(feedbacks).insert(any(ServiceFeedback.class));
  }

  @Test void invalidExpiredOrRevokedReceiptCannotReadStatusOrWriteFeedback() {
    when(receipts.requireValidInvoice("bad")).thenThrow(new IllegalArgumentException("Receipt unavailable"));
    assertThrows(IllegalArgumentException.class, () -> service.submit("bad", request("5", null)));
    assertThrows(IllegalArgumentException.class, () -> service.status("bad"));
    verifyNoInteractions(feedbacks);
  }

  @Test void duplicateOrLostResponseRetryReturnsConflictWithoutOverwrite() {
    when(feedbacks.existsByTenantIdAndInvoiceId("tenant-a", "invoice-a")).thenReturn(true);
    assertEquals("Service feedback already submitted",
        assertThrows(IllegalStateException.class, () -> service.submit("token-a", request("5", "retry"))).getMessage());
    verify(feedbacks, never()).insert(any(ServiceFeedback.class));
    verify(feedbacks, never()).save(any(ServiceFeedback.class));
    assertTrue(service.status("token-a").submitted());
  }

  @Test void multipleReceiptTokensForSameInvoiceStillShareOneFeedback() {
    when(receipts.requireValidInvoice("token-b")).thenReturn(invoice);
    when(feedbacks.existsByTenantIdAndInvoiceId("tenant-a", "invoice-a")).thenReturn(false, true);
    service.submit("token-a", request("5", null));
    assertThrows(IllegalStateException.class, () -> service.submit("token-b", request("1", "other")));
    verify(feedbacks, times(1)).insert(any(ServiceFeedback.class));
  }

  @Test void statusUsesVerifiedTenantAndInvoiceOnly() {
    assertFalse(service.status("token-a").submitted());
    verify(feedbacks).existsByTenantIdAndInvoiceId("tenant-a", "invoice-a");
    verify(feedbacks, never()).findById(any());
  }

  @Test void concurrentRequestsCannotCreateTwoFeedbacks() throws Exception {
    var stored = new ConcurrentHashMap<String, ServiceFeedback>();
    var ready = new CountDownLatch(2);
    var checks = new AtomicInteger();
    when(feedbacks.existsByTenantIdAndInvoiceId("tenant-a", "invoice-a")).thenAnswer(call -> {
      if (checks.incrementAndGet() <= 2) {
        ready.countDown(); assertTrue(ready.await(5, TimeUnit.SECONDS)); return false;
      }
      return stored.containsKey("tenant-a:invoice-a");
    });
    when(feedbacks.insert(any(ServiceFeedback.class))).thenAnswer(call -> {
      ServiceFeedback feedback = call.getArgument(0);
      if (stored.putIfAbsent("tenant-a:invoice-a", feedback) != null) throw new DuplicateKeyException("unique index");
      return feedback;
    });
    var executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> send = () -> {
        try { service.submit("token-a", request("5", null)); return 201; }
        catch (IllegalStateException ex) { return 409; }
      };
      var first = executor.submit(send); var second = executor.submit(send);
      var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertTrue(results.contains(201)); assertTrue(results.contains(409)); assertEquals(1, stored.size());
      verify(feedbacks, times(2)).insert(any(ServiceFeedback.class));
    } finally { executor.shutdownNow(); }
  }

  @Test void storageFailureOrUnrelatedUniqueErrorIsNotReportedAsSuccessOrFeedbackDuplicate() {
    when(feedbacks.insert(any(ServiceFeedback.class))).thenThrow(new DataAccessResourceFailureException("offline"));
    assertThrows(DataAccessResourceFailureException.class, () -> service.submit("token-a", request("5", null)));
    when(feedbacks.insert(any(ServiceFeedback.class))).thenThrow(new DuplicateKeyException("unrelated key"));
    assertThrows(DuplicateKeyException.class, () -> service.submit("token-a", request("5", null)));
  }

  private SubmitServiceFeedbackRequest request(String rating, String comment) {
    return new SubmitServiceFeedbackRequest(new BigDecimal(rating), comment);
  }
}
