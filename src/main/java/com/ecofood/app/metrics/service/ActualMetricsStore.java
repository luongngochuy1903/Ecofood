package com.ecofood.app.metrics.service;

import com.ecofood.app.metrics.entity.*;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.submission.entity.InvoiceBatchSubmission;
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

/** Legacy standalone projection store; runtime writes go to Proposition.actualMetrics. */
@Deprecated
public class ActualMetricsStore {
  private static final String TENANT_ID = "tenantId";
  private static final String REBUILD_LIMIT_EXCEEDED = "REBUILD_LIMIT_EXCEEDED_USE_CONTROLLED_MAINTENANCE";
  private static final String INVALID_BATCH = "BATCH_SUBMISSION_INVALID";
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
      processInTransaction(ops, submissionId, tenantId);
      return null;
    });
  }

  private void processInTransaction(MongoOperations ops, String submissionId, String tenantId) {
    var event = ops.findOne(sourceQuery(submissionId, tenantId), Submission.class);
    if (event == null) throw new KpiProcessingException("SUBMISSION_NOT_FOUND_IN_TENANT", false);
    var contributions = eventContributions(ops, event);
    var previous = processingState(ops, event);
    if (alreadyProjected(previous, contributions)) return;
    var now = Instant.now();
    long revision = updateBuckets(ops, contributions, now);
    markProjected(ops, event, contributions, revision, attempts(previous), now);
  }

  private boolean alreadyProjected(SubmissionMetricsState.Processing previous,
      List<KpiContribution> contributions) {
    if (previous == null || previous.projectedAt() == null) return false;
    if (!contributions.equals(previous.entries())) {
      throw new KpiProcessingException("PROJECTED_INVOICE_INPUT_CHANGED", false);
    }
    return true;
  }

  /** All bucket writes and the marker stay inside the caller's transaction. */
  private long updateBuckets(MongoOperations ops, List<KpiContribution> contributions, Instant now) {
    long revision = 0;
    var buckets = new TreeMap<String, ActualMetrics>();
    for (var contribution : contributions) {
      var current = buckets.get(contribution.key().id());
      if (current == null) current = ops.findById(contribution.key().id(), ActualMetrics.class);
      if (current == null) current = ActualMetrics.empty(contribution.key(), KpiCalculator.VERSION, now);
      var updated = current.add(contribution, now);
      buckets.put(updated.id(), updated);
      revision = Math.max(revision, updated.revision());
    }
    buckets.values().forEach(ops::save);
    return revision;
  }

  /** Read-only reconciliation of immutable Invoice snapshots in a consistent Mongo snapshot. */
  public Reconciliation reconcile(MetricsKey key) {
    return transactions.execute(ops -> reconcileInTransaction(ops, key));
  }

  private Reconciliation reconcileInTransaction(MongoOperations ops, MetricsKey key) {
    var invoices = bucketInvoices(ops, key);
    var expected = expected(key, invoices, Instant.now());
    var actual = ops.findById(key.id(), ActualMetrics.class);
    var issues = new ArrayList<String>();
    for (var invoice : invoices) {
      collectInvoiceIssues(ops, invoice, key.tenantId(), issues);
    }
    boolean totalsMatch = sameTotals(expected, actual);
    if (!totalsMatch) issues.add("MONTHLY_PROJECTION_MISMATCH");
    return new Reconciliation(key, expected, actual, totalsMatch && issues.isEmpty(), issues);
  }

  private void collectInvoiceIssues(MongoOperations ops, Invoice invoice, String tenant,
      List<String> issues) {
    var event = ops.findOne(sourceQuery(invoice.getSubmissionId(), tenant), Submission.class);
    try {
      validateSource(invoice, event);
      var entries = eventContributions(ops, event);
      requireMembership(invoice, entries);
      var state = processingState(ops, event);
      if (state == null || state.projectedAt() == null) {
        issues.add(invoice.getId() + ":CONTRIBUTION_NOT_PROJECTED");
      } else if (!entries.equals(state.entries())) {
        issues.add(invoice.getId() + ":CONTRIBUTION_INPUT_MISMATCH");
      }
    } catch (KpiProcessingException failure) {
      issues.add(invoice.getId() + ":" + failure.code());
    }
  }

  /** Rebuild the connected bucket/event set, so no partially rebuilt batch can be marked complete. */
  public Reconciliation rebuild(MetricsKey key) {
    return transactions.execute(ops -> rebuildInTransaction(ops, key));
  }

  private Reconciliation rebuildInTransaction(MongoOperations ops, MetricsKey key) {
    var graph = discoverConnectedBuckets(ops, key);
    var now = graph.createdAt;
    var projection = writeRebuiltBuckets(ops, graph.buckets, now);
    for (var event : graph.events.values()) {
      var previous = processingState(ops, event);
      markProjected(ops, event, graph.contributions.get(event.id()),
          projection.revision(), attempts(previous), now);
    }
    var result = projection.buckets().get(key);
    return new Reconciliation(key, result, result, true, List.of());
  }

  /** In-memory traversal state only; this is not another persisted model or collection. */
  private static final class RebuildGraph {
    private final Deque<MetricsKey> pending = new ArrayDeque<>();
    private final Map<MetricsKey, List<Invoice>> buckets = new LinkedHashMap<>();
    private final Map<String, Submission> events = new TreeMap<>();
    private final Map<String, List<KpiContribution>> contributions = new HashMap<>();
    private final Set<String> invoiceIds = new HashSet<>();
    private final Instant createdAt = Instant.now();
  }

  private RebuildGraph discoverConnectedBuckets(MongoOperations ops, MetricsKey key) {
    var graph = new RebuildGraph();
    graph.pending.add(key);
    while (!graph.pending.isEmpty()) {
      var bucket = graph.pending.removeFirst();
      if (graph.buckets.containsKey(bucket)) continue;
      var invoices = bucketInvoices(ops, bucket);
      graph.buckets.put(bucket, invoices);
      for (var invoice : invoices) {
        discoverInvoiceEvent(ops, invoice, key.tenantId(), graph);
      }
    }
    return graph;
  }

  private void discoverInvoiceEvent(MongoOperations ops, Invoice invoice, String tenant,
      RebuildGraph graph) {
    graph.invoiceIds.add(invoice.getId());
    if (graph.invoiceIds.size() > rebuildLimit || graph.buckets.size() > rebuildLimit) {
      throw new KpiProcessingException(REBUILD_LIMIT_EXCEEDED, false);
    }
    var event = graph.events.get(invoice.getSubmissionId());
    if (event != null) {
      validateSource(invoice, event);
      requireMembership(invoice, graph.contributions.get(event.id()));
      return;
    }
    event = loadOrRestoreEvent(ops, invoice, tenant);
    var entries = eventContributions(ops, event);
    requireMembership(invoice, entries);
    graph.events.put(event.id(), event);
    graph.contributions.put(event.id(), entries);
    for (var entry : entries) {
      if (!graph.buckets.containsKey(entry.key())) graph.pending.add(entry.key());
    }
  }

  private Submission loadOrRestoreEvent(MongoOperations ops, Invoice invoice, String tenant) {
    var event = ops.findOne(sourceQuery(invoice.getSubmissionId(), tenant), Submission.class);
    if (event == null) {
      event = invoice.getSubmissionHandoff();
      validateSource(invoice, event);
      ops.insert(event); // Legacy handoff only; batch events commit with all their Invoices.
    } else {
      validateSource(invoice, event);
    }
    return event;
  }

  private record BucketProjection(Map<MetricsKey, ActualMetrics> buckets, long revision) {}

  private BucketProjection writeRebuiltBuckets(MongoOperations ops,
      Map<MetricsKey, List<Invoice>> buckets, Instant now) {
    var rebuilt = new HashMap<MetricsKey, ActualMetrics>();
    long projectionRevision = 0;
    for (var bucket : buckets.keySet().stream().sorted(Comparator.comparing(MetricsKey::id)).toList()) {
      var total = expected(bucket, buckets.get(bucket), now);
      var current = ops.findById(bucket.id(), ActualMetrics.class);
      long revision = current == null ? 1 : Math.addExact(current.revision(), 1);
      projectionRevision = Math.max(projectionRevision, revision);
      var updated = withRevision(total, revision, now);
      ops.save(updated);
      rebuilt.put(bucket, updated);
    }
    return new BucketProjection(rebuilt, projectionRevision);
  }

  private ActualMetrics withRevision(ActualMetrics total, long revision, Instant now) {
    return new ActualMetrics(total.id(), total.propositionId(), total.tenantId(), total.year(),
        total.month(), total.currency(), total.totalSalesVolume(), total.ingredientCost(),
        total.platformCost(), total.profit(), total.knownTotals(), total.coverage(),
        total.calculatorVersion(), revision, now);
  }

  private SubmissionMetricsState.Processing processingState(MongoOperations ops, Submission event) {
    var view = ops.findOne(sourceQuery(event.id(), event.tenantId()), SubmissionMetricsState.class);
    return view == null ? null : view.actualMetrics();
  }

  private int attempts(SubmissionMetricsState.Processing previous) {
    return previous == null ? 0 : previous.attempts();
  }

  private void requireMembership(Invoice invoice, List<KpiContribution> entries) {
    if (entries.stream().noneMatch(c -> invoice.getId().equals(c.invoiceId()))) {
      throw new KpiProcessingException("INVOICE_NOT_IN_BATCH_SUBMISSION", false);
    }
  }

  private List<KpiContribution> eventContributions(MongoOperations ops, Submission event) {
    var rows = new ArrayList<Invoice>();
    if ("INVOICE_BATCH".equals(event.sourceType())) {
      rows.addAll(batchInvoices(ops, event));
    } else {
      var invoice = sourceInvoice(ops, event.sourceId(), event.tenantId());
      validateSource(invoice, event);
      rows.add(invoice);
    }
    return rows.stream().flatMap(invoice -> calculator.calculateAll(invoice).stream()).toList();
  }

  private List<Invoice> batchInvoices(MongoOperations ops, Submission event) {
    var batch = ops.findOne(sourceQuery(event.id(), event.tenantId()), InvoiceBatchSubmission.class);
    validateBatch(batch, event);
    var rows = new ArrayList<Invoice>();
    for (String id : batch.invoiceIds().stream().sorted().toList()) {
      var invoice = sourceInvoice(ops, id, event.tenantId());
      validateSource(invoice, event);
      if (!"PLAN_SALE_BATCH".equals(invoice.getType())
          || !Objects.equals(invoice.getSubmittedBy(), batch.submittedBy())) {
        throw new KpiProcessingException("BATCH_INVOICE_SCOPE_MISMATCH", false);
      }
      rows.add(invoice);
    }
    return rows;
  }

  private Invoice sourceInvoice(MongoOperations ops, String id, String tenant) {
    return ops.findOne(sourceQuery(id, tenant), Invoice.class);
  }

  private void validateBatch(InvoiceBatchSubmission batch, Submission event) {
    if (batch == null || batch.invoiceIds().isEmpty() || batch.invoiceIds().size() > 100
        || new HashSet<>(batch.invoiceIds()).size() != batch.invoiceIds().size()
        || !event.equals(batch.envelope())) {
      throw new KpiProcessingException(INVALID_BATCH, false);
    }
  }

  private List<Invoice> bucketInvoices(MongoOperations ops, MetricsKey key) {
    var start = LocalDate.of(key.year(), key.month(), 1).atStartOfDay(KpiCalculator.ZONE).toInstant();
    var end = LocalDate.of(key.year(), key.month(), 1).plusMonths(1).atStartOfDay(KpiCalculator.ZONE).toInstant();
    var criteria = Criteria.where(TENANT_ID).is(key.tenantId()).and("currency").is(key.currency())
        .and("receivedAt").gte(start).lt(end).and("status").is(TransactionStatus.ACCEPTED)
        .and("paymentStatus").is("PAID");
    criteria.orOperator(Criteria.where("propositionId").is(key.propositionId()),
        Criteria.where("planLines.snapshot.propositionId").is(key.propositionId()));
    var rows = ops.find(Query.query(criteria).limit(rebuildLimit + 1), Invoice.class);
    if (rows.size() > rebuildLimit) throw new KpiProcessingException(REBUILD_LIMIT_EXCEEDED, false);
    return rows;
  }

  private ActualMetrics expected(MetricsKey key, List<Invoice> invoices, Instant now) {
    var total = ActualMetrics.empty(key, KpiCalculator.VERSION, now);
    for (var invoice : invoices) {
      for (var contribution : calculator.calculateAll(invoice)) {
        if (key.equals(contribution.key())) {
          total = total.add(contribution, now);
        }
      }
    }
    return total;
  }

  private void markProjected(MongoOperations ops, Submission event, List<KpiContribution> contributions,
      long revision, int attempts, Instant now) {
    var state = new SubmissionMetricsState.Processing(
        contributions.stream().allMatch(KpiContribution::complete) ? "SUCCEEDED" : "PARTIAL",
        contributions.size() == 1 ? contributions.get(0) : null, now, revision,
        Math.addExact(attempts, 1), null, null,
        contributions.size() == 1 ? null : List.copyOf(contributions));
    var result = ops.updateFirst(sourceQuery(event.id(), event.tenantId()),
        new Update().set("actualMetrics", state), Submission.class);
    if (!result.wasAcknowledged() || result.getMatchedCount() != 1) {
      throw new KpiProcessingException("SUBMISSION_MARKER_NOT_WRITTEN", true);
    }
  }

  private Query sourceQuery(String id, String tenant) {
    return Query.query(Criteria.where("_id").is(id).and(TENANT_ID).is(tenant));
  }

  private void validateSource(Invoice invoice, Submission event) {
    if (invoice == null) throw new KpiProcessingException("SOURCE_INVOICE_NOT_FOUND", false);
    if (event == null || !Submission.INVOICE_PAID.equals(event.event())
        || !Objects.equals(event.id(), invoice.getSubmissionId())
        || !Objects.equals(event.tenantId(), invoice.getTenantId())
        || !Objects.equals(event.createdAt(), invoice.getReceivedAt())) {
      throw new KpiProcessingException("SUBMISSION_INVOICE_SCOPE_MISMATCH", false);
    }
    if ("INVOICE_BATCH".equals(event.sourceType())) {
      if (!event.id().equals(event.sourceId()) || !"PLAN_SALE_BATCH".equals(invoice.getType())) {
        throw new KpiProcessingException(INVALID_BATCH, false);
      }
      return;
    }
    if (!"INVOICE".equals(event.sourceType())
        || !Objects.equals(event.id(), invoice.getId() + ":" + Submission.INVOICE_PAID)
        || !Objects.equals(event.sourceId(), invoice.getId())
        || !Objects.equals(event.propositionId(), invoice.getPropositionId())
        || !Objects.equals(event.planId(), invoice.getPlanId())
        || !Objects.equals(event.orchestratorId(), invoice.getOrchestratorId())
        || event.orchestratorVersion() != invoice.getOrchestratorVersion()) {
      throw new KpiProcessingException("SUBMISSION_INVOICE_SCOPE_MISMATCH", false);
    }
  }
  private boolean sameTotals(ActualMetrics expected,ActualMetrics actual) {
    if(actual==null) return expected.totalSalesVolume()==0;
    return expected.key().equals(actual.key()) && expected.totalSalesVolume()==actual.totalSalesVolume()
        && Objects.equals(expected.calculatorVersion(),actual.calculatorVersion()) && Objects.equals(expected.coverage(),actual.coverage())
        && equalAmount(expected.ingredientCost(),actual.ingredientCost()) && equalAmount(expected.platformCost(),actual.platformCost())
        && equalAmount(expected.profit(),actual.profit()) && actual.knownTotals()!=null
        && equalAmount(expected.knownTotals().ingredientCost(),actual.knownTotals().ingredientCost())
        && equalAmount(expected.knownTotals().platformCost(),actual.knownTotals().platformCost())
        && equalAmount(expected.knownTotals().profit(),actual.knownTotals().profit());
  }
  private boolean equalAmount(java.math.BigDecimal a,java.math.BigDecimal b) { return a==null || b==null?a==b:a.compareTo(b)==0; }
  public record Reconciliation(MetricsKey key,ActualMetrics expected,ActualMetrics actual,boolean consistent,List<String> issues) {
    public Reconciliation { issues=List.copyOf(issues); }
  }
}
