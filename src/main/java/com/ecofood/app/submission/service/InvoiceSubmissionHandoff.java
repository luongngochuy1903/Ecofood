package com.ecofood.app.submission.service;

import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.submission.repository.SubmissionRepository;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.enums.TransactionStatus;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceSubmissionHandoff {
  private final SubmissionRepository submissions;
  private final InvoiceRepository invoices;
  private final MongoTemplate mongo;

  /** Called before insert: this event is committed atomically with its source Invoice. */
  public void prepare(Invoice invoice) {
    String eventId = invoice.getId() + ":" + Submission.INVOICE_PAID;
    invoice.setSubmissionId(eventId);
    invoice.setSubmissionHandoff(new Submission(eventId, Submission.INVOICE_PAID,
        invoice.getReceivedAt(), invoice.getTenantId(), "INVOICE", invoice.getId(),
        invoice.getPropositionId(), invoice.getPlanId(), invoice.getOrchestratorId(),
        invoice.getOrchestratorVersion()));
    invoice.setSubmissionPending(true);
    invoice.setSubmissionRetryAt(invoice.getReceivedAt());
  }

  /** Failure after Invoice commit is recoverable and must not turn a saved sale into a failed API call. */
  public void deliver(Invoice invoice) {
    try {
      var event = invoice.getSubmissionHandoff();
      validate(invoice, event);
      try {
        submissions.insert(event);
      } catch (DuplicateKeyException ex) {
        var existing = submissions.findByIdAndTenantId(event.id(), invoice.getTenantId());
        if (existing.isEmpty() || !event.equals(existing.get()))
          throw new IllegalStateException("Submission source conflict");
      }
      mongo.updateFirst(pending(invoice), new Update().set("submissionPending", false)
          .unset("submissionRetryAt").inc("submissionAttempts", 1), Invoice.class);
    } catch (RuntimeException ex) {
      log.warn("Invoice Submission deferred for {} ({})", invoice.getId(), ex.getClass().getSimpleName());
      try {
        // Conditional update cannot reopen a handoff acknowledged by a concurrent delivery.
        mongo.updateFirst(pending(invoice), new Update()
            .set("submissionRetryAt", Instant.now().plusSeconds(60))
            .inc("submissionAttempts", 1), Invoice.class);
      } catch (RuntimeException retryFailure) {
        // The original pending event remains on the Invoice even when this bookkeeping write fails.
        log.warn("Submission retry bookkeeping deferred for {} ({})", invoice.getId(),
            retryFailure.getClass().getSimpleName());
      }
    }
  }

  @Scheduled(fixedDelayString = "${ecofood.submissions.handoff-retry-ms:10000}",
      initialDelayString = "${ecofood.submissions.handoff-initial-delay-ms:15000}")
  public void recover() {
    try {
      var pending = invoices.findBySubmissionPendingTrueAndSubmissionRetryAtLessThanEqual(
          Instant.now(), PageRequest.of(0, 100, Sort.by("submissionRetryAt", "id")));
      pending.forEach(this::deliver);
    } catch (RuntimeException ex) {
      log.warn("Invoice Submission recovery deferred ({})", ex.getClass().getSimpleName());
    }
  }

  private Query pending(Invoice invoice) {
    return Query.query(Criteria.where("_id").is(invoice.getId())
        .and("tenantId").is(invoice.getTenantId())
        .and("submissionId").is(invoice.getSubmissionId()).and("submissionPending").is(true));
  }

  private void validate(Invoice invoice, Submission event) {
    if (event == null || invoice.getStatus() != TransactionStatus.ACCEPTED
        || !"PAID".equals(invoice.getPaymentStatus())
        || !Objects.equals(event.id(), invoice.getSubmissionId())
        || !Objects.equals(event.id(), invoice.getId() + ":" + Submission.INVOICE_PAID)
        || !Submission.INVOICE_PAID.equals(event.event()) || !"INVOICE".equals(event.sourceType())
        || !Objects.equals(event.sourceId(), invoice.getId())
        || !Objects.equals(event.tenantId(), invoice.getTenantId())
        || !Objects.equals(event.propositionId(), invoice.getPropositionId())
        || !Objects.equals(event.planId(), invoice.getPlanId())
        || !Objects.equals(event.orchestratorId(), invoice.getOrchestratorId())
        || event.orchestratorVersion() != invoice.getOrchestratorVersion()
        || !Objects.equals(event.createdAt(), invoice.getReceivedAt()))
      throw new IllegalStateException("Invalid Invoice Submission handoff");
  }
}
