package com.ecofood.app.metrics.service;

import com.ecofood.app.metrics.entity.*;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.enums.TransactionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class ActualMetricsStore {
  private final MongoMetricsTransactions transactions;
  private final KpiCalculator calculator;
  private final int rebuildLimit;

  public ActualMetricsStore(MongoMetricsTransactions transactions, KpiCalculator calculator,
      @Value("${ecofood.metrics.rebuild-max-invoices:1000}") int rebuildLimit) {
    this.transactions = transactions;
    this.calculator = calculator;
    if (rebuildLimit < 1) throw new IllegalArgumentException("Invalid metrics rebuild limit");
    this.rebuildLimit = rebuildLimit;
  }

  public void process(String submissionId, String tenantId) {
    transactions.execute(ops -> {
      var event = ops.findOne(sourceQuery(submissionId, tenantId), Submission.class);
      if (event == null) throw new KpiProcessingException("SUBMISSION_NOT_FOUND_IN_TENANT", false);
      var invoice = ops.findOne(Query.query(Criteria.where("_id").is(event.sourceId())
          .and("tenantId").is(tenantId)), Invoice.class);
      validateSource(invoice, event);
      var contribution = calculator.calculate(invoice);
      var view = ops.findOne(sourceQuery(submissionId, tenantId), SubmissionMetricsState.class);
      var previous = view == null ? null : view.actualMetrics();
      if (previous != null && previous.projectedAt() != null) {
        if (!contribution.equals(previous.contribution()))
          throw new KpiProcessingException("PROJECTED_INVOICE_INPUT_CHANGED", false);
        return null; // Duplicate delivery, including an ambiguous commit outcome.
      }
      var now = Instant.now();
      var current = ops.findById(contribution.key().id(), ActualMetrics.class);
      if (current == null) current = ActualMetrics.empty(contribution.key(), KpiCalculator.VERSION, now);
      var updated = current.add(contribution, now);
      ops.save(updated);
      markProjected(ops, event, contribution, updated.revision(), previous == null ? 0 : previous.attempts(), now);
      return null;
    });
  }

  /** Read-only audit from historical Invoices, in a consistent transactional snapshot. */
  public Reconciliation reconcile(MetricsKey key) {
    return transactions.execute(ops -> {
      var invoices = bucketInvoices(ops, key);
      var expected = expected(key, invoices, Instant.now());
      var actual = ops.findById(key.id(), ActualMetrics.class);
      var issues = new ArrayList<String>();
      for (var invoice : invoices) {
        var event = ops.findOne(sourceQuery(invoice.getSubmissionId(), key.tenantId()), Submission.class);
        try { validateSource(invoice, event); }
        catch (KpiProcessingException ex) { issues.add(invoice.getId() + ":" + ex.code()); continue; }
        var state = ops.findOne(sourceQuery(event.id(), key.tenantId()), SubmissionMetricsState.class);
        if (state == null || state.actualMetrics() == null || state.actualMetrics().projectedAt() == null)
          issues.add(invoice.getId() + ":CONTRIBUTION_NOT_PROJECTED");
        else if (!calculator.calculate(invoice).equals(state.actualMetrics().contribution()))
          issues.add(invoice.getId() + ":CONTRIBUTION_INPUT_MISMATCH");
      }
      boolean totalsMatch = sameTotals(expected, actual);
      if (!totalsMatch) issues.add("MONTHLY_PROJECTION_MISMATCH");
      return new Reconciliation(key, expected, actual, totalsMatch && issues.isEmpty(), issues);
    });
  }

  /** Explicit internal operation only. Never scheduled as a destructive production repair. */
  public Reconciliation rebuild(MetricsKey key) {
    return transactions.execute(ops -> {
      var invoices = bucketInvoices(ops, key);
      var now = Instant.now();
      var rebuilt = expected(key, invoices, now);
      var current = ops.findById(key.id(), ActualMetrics.class);
      long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
      rebuilt = new ActualMetrics(rebuilt.id(), rebuilt.propositionId(), rebuilt.tenantId(),
          rebuilt.year(), rebuilt.month(), rebuilt.currency(), rebuilt.totalSalesVolume(),
          rebuilt.ingredientCost(), rebuilt.platformCost(), rebuilt.profit(), rebuilt.knownTotals(),
          rebuilt.coverage(), rebuilt.calculatorVersion(), revision, now);
      // All writes, including materialization of a missing Task 3A event, commit or abort together.
      for (var invoice : invoices) {
        var event = ops.findOne(sourceQuery(invoice.getSubmissionId(), key.tenantId()), Submission.class);
        if (event == null) {
          event = invoice.getSubmissionHandoff();
          validateSource(invoice, event);
          ops.insert(event);
        } else validateSource(invoice, event);
        var view = ops.findOne(sourceQuery(event.id(), key.tenantId()), SubmissionMetricsState.class);
        int attempts = view == null || view.actualMetrics() == null ? 0 : view.actualMetrics().attempts();
        markProjected(ops, event, calculator.calculate(invoice), revision, attempts, now);
      }
      // Contends on the SAME bucket document as process(): stale rebuilds cannot overwrite new commits.
      ops.save(rebuilt);
      return new Reconciliation(key, rebuilt, rebuilt, true, List.of());
    });
  }

  private List<Invoice> bucketInvoices(MongoOperations ops, MetricsKey key) {
    var start = LocalDate.of(key.year(), key.month(), 1).atStartOfDay(KpiCalculator.ZONE).toInstant();
    var end = LocalDate.of(key.year(), key.month(), 1).plusMonths(1)
        .atStartOfDay(KpiCalculator.ZONE).toInstant();
    var query = Query.query(Criteria.where("propositionId").is(key.propositionId())
        .and("tenantId").is(key.tenantId()).and("currency").is(key.currency())
        .and("receivedAt").gte(start).lt(end).and("status").is(TransactionStatus.ACCEPTED)
        .and("paymentStatus").is("PAID")).limit(rebuildLimit + 1);
    var rows = ops.find(query, Invoice.class);
    if (rows.size() > rebuildLimit)
      throw new KpiProcessingException("REBUILD_LIMIT_EXCEEDED_USE_CONTROLLED_MAINTENANCE", false);
    return rows;
  }

  private ActualMetrics expected(MetricsKey key, List<Invoice> invoices, Instant now) {
    var total = ActualMetrics.empty(key, KpiCalculator.VERSION, now);
    for (var invoice : invoices) total = total.add(calculator.calculate(invoice), now);
    return total;
  }

  private void markProjected(MongoOperations ops, Submission event, KpiContribution contribution,
      long revision, int attempts, Instant now) {
    var state = new SubmissionMetricsState.Processing(contribution.complete() ? "SUCCEEDED" : "PARTIAL",
        contribution, now, revision, Math.addExact(attempts, 1), null, null);
    var result = ops.updateFirst(sourceQuery(event.id(), event.tenantId()),
        new Update().set("actualMetrics", state), Submission.class);
    if (!result.wasAcknowledged() || result.getMatchedCount() != 1)
      throw new KpiProcessingException("SUBMISSION_MARKER_NOT_WRITTEN", true);
  }

  private Query sourceQuery(String id, String tenant) {
    return Query.query(Criteria.where("_id").is(id).and("tenantId").is(tenant));
  }

  private void validateSource(Invoice invoice, Submission event) {
    if (invoice == null) throw new KpiProcessingException("SOURCE_INVOICE_NOT_FOUND", false);
    if (event == null || !Submission.INVOICE_PAID.equals(event.event()) || !"INVOICE".equals(event.sourceType())
        || !Objects.equals(event.id(), invoice.getId() + ":" + Submission.INVOICE_PAID)
        || !Objects.equals(event.id(), invoice.getSubmissionId())
        || !Objects.equals(event.sourceId(), invoice.getId())
        || !Objects.equals(event.tenantId(), invoice.getTenantId())
        || !Objects.equals(event.propositionId(), invoice.getPropositionId())
        || !Objects.equals(event.planId(), invoice.getPlanId())
        || !Objects.equals(event.orchestratorId(), invoice.getOrchestratorId())
        || event.orchestratorVersion() != invoice.getOrchestratorVersion()
        || !Objects.equals(event.createdAt(), invoice.getReceivedAt()))
      throw new KpiProcessingException("SUBMISSION_INVOICE_SCOPE_MISMATCH", false);
  }

  private boolean sameTotals(ActualMetrics expected, ActualMetrics actual) {
    if (actual == null) return expected.totalSalesVolume() == 0;
    return expected.key().equals(actual.key()) && expected.totalSalesVolume() == actual.totalSalesVolume()
        && Objects.equals(expected.calculatorVersion(), actual.calculatorVersion())
        && Objects.equals(expected.coverage(), actual.coverage())
        && equalAmount(expected.ingredientCost(), actual.ingredientCost())
        && equalAmount(expected.platformCost(), actual.platformCost())
        && equalAmount(expected.profit(), actual.profit())
        && actual.knownTotals() != null
        && equalAmount(expected.knownTotals().ingredientCost(), actual.knownTotals().ingredientCost())
        && equalAmount(expected.knownTotals().platformCost(), actual.knownTotals().platformCost())
        && equalAmount(expected.knownTotals().profit(), actual.knownTotals().profit());
  }

  private boolean equalAmount(java.math.BigDecimal a, java.math.BigDecimal b) {
    return a == null || b == null ? a == b : a.compareTo(b) == 0;
  }

  public record Reconciliation(MetricsKey key, ActualMetrics expected, ActualMetrics actual,
                               boolean consistent, List<String> issues) {
    public Reconciliation { issues = List.copyOf(issues); }
  }
}
