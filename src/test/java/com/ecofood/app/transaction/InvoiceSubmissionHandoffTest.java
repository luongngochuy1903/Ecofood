package com.ecofood.app.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.submission.repository.SubmissionRepository;
import com.ecofood.app.submission.service.InvoiceSubmissionHandoff;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.enums.TransactionStatus;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class InvoiceSubmissionHandoffTest {
  private final SubmissionRepository submissions = mock(SubmissionRepository.class);
  private final InvoiceRepository invoices = mock(InvoiceRepository.class);
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final InvoiceSubmissionHandoff service = new InvoiceSubmissionHandoff(submissions, invoices, mongo);

  private Invoice invoice() {
    var invoice = new Invoice();
    invoice.setId("invoice");
    invoice.setTenantId("tenant");
    invoice.setPropositionId("prop");
    invoice.setPlanId("plan");
    invoice.setOrchestratorId("orch");
    invoice.setOrchestratorVersion(2);
    invoice.setReceivedAt(Instant.parse("2026-11-20T00:00:00Z"));
    invoice.setStatus(TransactionStatus.ACCEPTED);
    service.prepare(invoice);
    return invoice;
  }

  @Test
  void preparesDurableEventBeforeInsertAndDeliversOneBusinessEvent() {
    var invoice = invoice();
    assertTrue(invoice.isSubmissionPending());
    assertEquals("invoice:INVOICE_PAID", invoice.getSubmissionId());
    assertEquals(invoice.getReceivedAt(), invoice.getSubmissionHandoff().createdAt());
    assertEquals("invoice", invoice.getSubmissionHandoff().sourceId());
    assertEquals("tenant", invoice.getSubmissionHandoff().tenantId());
    verifyNoInteractions(submissions, mongo);
    service.deliver(invoice);
    var order = inOrder(submissions, mongo);
    order.verify(submissions).insert(invoice.getSubmissionHandoff());
    order.verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(Invoice.class));
  }

  @Test
  void recoversAfterInvoiceCommitEvenIfSubmissionInsertAndRetryBookkeepingFail() {
    var invoice = invoice();
    when(submissions.insert(any(Submission.class)))
        .thenThrow(new DataAccessResourceFailureException("offline"))
        .thenAnswer(call -> call.getArgument(0));
    when(mongo.updateFirst(any(Query.class), any(Update.class), eq(Invoice.class)))
        .thenThrow(new DataAccessResourceFailureException("offline"))
        .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
    assertDoesNotThrow(() -> service.deliver(invoice));
    assertTrue(invoice.isSubmissionPending());
    assertNotNull(invoice.getSubmissionHandoff());
    when(invoices.findBySubmissionPendingTrueAndSubmissionRetryAtLessThanEqual(any(), any()))
        .thenReturn(List.of(invoice));
    service.recover();
    verify(submissions, times(2)).insert(invoice.getSubmissionHandoff());
    var updates = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(mongo, times(2)).updateFirst(any(Query.class), updates.capture(), eq(Invoice.class));
    assertEquals(false, updates.getAllValues().get(1).getUpdateObject()
        .get("$set", org.bson.Document.class).get("submissionPending"));
  }

  @Test
  void retryAfterSubmissionCommitBeforeAcknowledgementUsesExistingEvent() {
    var invoice = invoice();
    when(submissions.insert(any(Submission.class)))
        .thenThrow(new DuplicateKeyException("already committed"));
    when(submissions.findByIdAndTenantId(invoice.getSubmissionId(), "tenant"))
        .thenReturn(Optional.of(invoice.getSubmissionHandoff()));
    service.deliver(invoice);
    var update = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateFirst(any(Query.class), update.capture(), eq(Invoice.class));
    assertEquals(false, update.getValue().getUpdateObject()
        .get("$set", org.bson.Document.class).get("submissionPending"));
  }

  @Test
  void concurrentDeliveriesHaveOnePersistedIdentityAndDoNotOverwriteConsumers() throws Exception {
    var invoice = invoice();
    var stored = new ConcurrentHashMap<String, Submission>();
    when(submissions.insert(any(Submission.class))).thenAnswer(call -> {
      Submission event = call.getArgument(0);
      if (stored.putIfAbsent(event.id(), event) != null) throw new DuplicateKeyException("race");
      return event;
    });
    when(submissions.findByIdAndTenantId(anyString(), eq("tenant")))
        .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
    var executor = Executors.newFixedThreadPool(2);
    try {
      var jobs = executor.invokeAll(List.of(
          () -> { service.deliver(invoice); return true; },
          () -> { service.deliver(invoice); return true; }));
      for (var job : jobs) job.get();
      assertEquals(1, stored.size());
      verify(submissions, never()).save(any());
    } finally { executor.shutdownNow(); }
  }

  @Test
  void mismatchedTenantCannotPublishOrAcknowledgeAnotherTenantsEvent() {
    var invoice = invoice();
    invoice.setTenantId("other");
    service.deliver(invoice);
    verifyNoInteractions(submissions);
    var query = org.mockito.ArgumentCaptor.forClass(Query.class);
    var update = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateFirst(query.capture(), update.capture(), eq(Invoice.class));
    assertEquals("other", query.getValue().getQueryObject().getString("tenantId"));
    assertEquals(true, query.getValue().getQueryObject().getBoolean("submissionPending"));
    assertFalse(update.getValue().getUpdateObject().get("$set", org.bson.Document.class)
        .containsKey("submissionPending"));
  }

  @Test
  void conflictingExistingEventRemainsPendingAndIsNeverOverwritten() {
    var invoice = invoice();
    when(submissions.insert(any(Submission.class))).thenThrow(new DuplicateKeyException("conflict"));
    when(submissions.findByIdAndTenantId(invoice.getSubmissionId(), "tenant"))
        .thenReturn(Optional.empty());
    service.deliver(invoice);
    verify(submissions, never()).save(any());
    var update = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateFirst(any(Query.class), update.capture(), eq(Invoice.class));
    assertFalse(update.getValue().getUpdateObject().get("$set", org.bson.Document.class)
        .containsKey("submissionPending"));
  }
}
