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
import org.springframework.stereotype.Service;

@Service
public class ActualMetricsStore {
  private final MongoMetricsTransactions transactions;
  private final KpiCalculator calculator;
  private final int rebuildLimit;

  public ActualMetricsStore(MongoMetricsTransactions transactions, KpiCalculator calculator,
      @Value("${ecofood.metrics.rebuild-max-invoices:1000}") int rebuildLimit) {
    this.transactions = transactions; this.calculator = calculator;
    if (rebuildLimit < 1) throw new IllegalArgumentException("Invalid metrics rebuild limit");
    this.rebuildLimit = rebuildLimit;
  }

  public void process(String submissionId, String tenantId) {
    transactions.execute(ops -> {
      var event = ops.findOne(sourceQuery(submissionId, tenantId), Submission.class);
      if (event == null) throw new KpiProcessingException("SUBMISSION_NOT_FOUND_IN_TENANT", false);
      var contributions=eventContributions(ops,event);
      var view = ops.findOne(sourceQuery(submissionId, tenantId), SubmissionMetricsState.class);
      var previous = view == null ? null : view.actualMetrics();
      if (previous != null && previous.projectedAt() != null) {
        if (!contributions.equals(previous.entries())) throw new KpiProcessingException("PROJECTED_INVOICE_INPUT_CHANGED", false);
        return null;
      }
      var now = Instant.now(); long revision=0;
      // Same transaction for ALL Invoices, ALL Proposition buckets and the single consumer marker.
      var buckets=new TreeMap<String,ActualMetrics>();
      for(var contribution:contributions) {
        var current=buckets.get(contribution.key().id());
        if(current==null) current=ops.findById(contribution.key().id(),ActualMetrics.class);
        if(current==null) current=ActualMetrics.empty(contribution.key(),KpiCalculator.VERSION,now);
        var updated=current.add(contribution,now); buckets.put(updated.id(),updated);
        revision=Math.max(revision,updated.revision());
      }
      buckets.values().forEach(ops::save);
      markProjected(ops,event,contributions,revision,previous==null?0:previous.attempts(),now);
      return null;
    });
  }

  /** Read-only reconciliation of immutable Invoice snapshots in a consistent Mongo snapshot. */
  public Reconciliation reconcile(MetricsKey key) {
    return transactions.execute(ops -> {
      var invoices=bucketInvoices(ops,key);
      var expected=expected(key,invoices,Instant.now());
      var actual=ops.findById(key.id(),ActualMetrics.class); var issues=new ArrayList<String>();
      for(var invoice:invoices) {
        var event=ops.findOne(sourceQuery(invoice.getSubmissionId(),key.tenantId()),Submission.class);
        try {
          validateSource(invoice,event);
          var entries=eventContributions(ops,event);
          if(entries.stream().noneMatch(c->invoice.getId().equals(c.invoiceId())))
            throw new KpiProcessingException("INVOICE_NOT_IN_BATCH_SUBMISSION",false);
          var state=ops.findOne(sourceQuery(event.id(),key.tenantId()),SubmissionMetricsState.class);
          if(state==null || state.actualMetrics()==null || state.actualMetrics().projectedAt()==null)
            issues.add(invoice.getId()+":CONTRIBUTION_NOT_PROJECTED");
          else if(!entries.equals(state.actualMetrics().entries())) issues.add(invoice.getId()+":CONTRIBUTION_INPUT_MISMATCH");
        } catch(KpiProcessingException ex) { issues.add(invoice.getId()+":"+ex.code()); }
      }
      boolean totalsMatch=sameTotals(expected,actual);
      if(!totalsMatch) issues.add("MONTHLY_PROJECTION_MISMATCH");
      return new Reconciliation(key,expected,actual,totalsMatch && issues.isEmpty(),issues);
    });
  }

  /** Rebuild the connected bucket/event set, so no partially rebuilt batch can be marked complete. */
  public Reconciliation rebuild(MetricsKey key) {
    return transactions.execute(ops -> {
      var queue=new ArrayDeque<MetricsKey>(); queue.add(key);
      var buckets=new LinkedHashMap<MetricsKey,List<Invoice>>();
      var events=new TreeMap<String,Submission>(); var contributions=new HashMap<String,List<KpiContribution>>();
      var invoiceIds=new HashSet<String>(); var now=Instant.now();
      while(!queue.isEmpty()) {
        var bucket=queue.removeFirst(); if(buckets.containsKey(bucket)) continue;
        var invoices=bucketInvoices(ops,bucket); buckets.put(bucket,invoices);
        for(var invoice:invoices) {
          invoiceIds.add(invoice.getId());
          if(invoiceIds.size()>rebuildLimit || buckets.size()>rebuildLimit) throw new KpiProcessingException("REBUILD_LIMIT_EXCEEDED_USE_CONTROLLED_MAINTENANCE",false);
          var event=events.get(invoice.getSubmissionId());
          if(event==null) {
            event=ops.findOne(sourceQuery(invoice.getSubmissionId(),key.tenantId()),Submission.class);
            if(event==null) {
              event=invoice.getSubmissionHandoff(); validateSource(invoice,event);
              ops.insert(event); // Legacy Invoice handoff only; batch event is committed with all Invoices.
            } else validateSource(invoice,event);
            var entries=eventContributions(ops,event);
            if(entries.stream().noneMatch(c->invoice.getId().equals(c.invoiceId())))
              throw new KpiProcessingException("INVOICE_NOT_IN_BATCH_SUBMISSION",false);
            events.put(event.id(),event); contributions.put(event.id(),entries);
            for(var entry:entries) if(!buckets.containsKey(entry.key())) queue.add(entry.key());
          } else {
            validateSource(invoice,event);
            if(contributions.get(event.id()).stream().noneMatch(c->invoice.getId().equals(c.invoiceId())))
              throw new KpiProcessingException("INVOICE_NOT_IN_BATCH_SUBMISSION",false);
          }
        }
      }
      var rebuilt=new HashMap<MetricsKey,ActualMetrics>(); long projectionRevision=0;
      for(var bucket:buckets.keySet().stream().sorted(Comparator.comparing(MetricsKey::id)).toList()) {
        var total=expected(bucket,buckets.get(bucket),now); var current=ops.findById(bucket.id(),ActualMetrics.class);
        long revision=current==null?1:Math.addExact(current.revision(),1); projectionRevision=Math.max(projectionRevision,revision);
        var updated=new ActualMetrics(total.id(),total.propositionId(),total.tenantId(),total.year(),total.month(),total.currency(),
            total.totalSalesVolume(),total.ingredientCost(),total.platformCost(),total.profit(),total.knownTotals(),total.coverage(),total.calculatorVersion(),revision,now);
        ops.save(updated); rebuilt.put(bucket,updated);
      }
      for(var event:events.values()) {
        var view=ops.findOne(sourceQuery(event.id(),key.tenantId()),SubmissionMetricsState.class);
        int attempts=view==null || view.actualMetrics()==null?0:view.actualMetrics().attempts();
        markProjected(ops,event,contributions.get(event.id()),projectionRevision,attempts,now);
      }
      return new Reconciliation(key,rebuilt.get(key),rebuilt.get(key),true,List.of());
    });
  }

  private List<KpiContribution> eventContributions(MongoOperations ops,Submission event) {
    var rows=new ArrayList<Invoice>();
    if("INVOICE_BATCH".equals(event.sourceType())) {
      var batch=ops.findOne(sourceQuery(event.id(),event.tenantId()),InvoiceBatchSubmission.class);
      if(batch==null || batch.invoiceIds().isEmpty() || batch.invoiceIds().size()>100
          || new HashSet<>(batch.invoiceIds()).size()!=batch.invoiceIds().size() || !event.equals(batch.envelope()))
        throw new KpiProcessingException("BATCH_SUBMISSION_INVALID",false);
      for(String id:batch.invoiceIds().stream().sorted().toList()) {
        var invoice=ops.findOne(Query.query(Criteria.where("_id").is(id).and("tenantId").is(event.tenantId())),Invoice.class);
        validateSource(invoice,event);
        if(!"PLAN_SALE_BATCH".equals(invoice.getType()) || !Objects.equals(invoice.getSubmittedBy(),batch.submittedBy()))
          throw new KpiProcessingException("BATCH_INVOICE_SCOPE_MISMATCH",false);
        rows.add(invoice);
      }
    } else {
      var invoice=ops.findOne(Query.query(Criteria.where("_id").is(event.sourceId()).and("tenantId").is(event.tenantId())),Invoice.class);
      validateSource(invoice,event); rows.add(invoice);
    }
    return rows.stream().flatMap(invoice->calculator.calculateAll(invoice).stream()).toList();
  }

  private List<Invoice> bucketInvoices(MongoOperations ops,MetricsKey key) {
    var start=LocalDate.of(key.year(),key.month(),1).atStartOfDay(KpiCalculator.ZONE).toInstant();
    var end=LocalDate.of(key.year(),key.month(),1).plusMonths(1).atStartOfDay(KpiCalculator.ZONE).toInstant();
    var criteria=Criteria.where("tenantId").is(key.tenantId()).and("currency").is(key.currency())
        .and("receivedAt").gte(start).lt(end).and("status").is(TransactionStatus.ACCEPTED).and("paymentStatus").is("PAID");
    criteria.orOperator(Criteria.where("propositionId").is(key.propositionId()),
        Criteria.where("planLines.snapshot.propositionId").is(key.propositionId()));
    var rows=ops.find(Query.query(criteria).limit(rebuildLimit+1),Invoice.class);
    if(rows.size()>rebuildLimit) throw new KpiProcessingException("REBUILD_LIMIT_EXCEEDED_USE_CONTROLLED_MAINTENANCE",false);
    return rows;
  }
  private ActualMetrics expected(MetricsKey key,List<Invoice> invoices,Instant now) {
    var total=ActualMetrics.empty(key,KpiCalculator.VERSION,now);
    for(var invoice:invoices) for(var contribution:calculator.calculateAll(invoice))
      if(key.equals(contribution.key())) total=total.add(contribution,now);
    return total;
  }
  private void markProjected(MongoOperations ops,Submission event,List<KpiContribution> contributions,long revision,int attempts,Instant now) {
    var state=new SubmissionMetricsState.Processing(contributions.stream().allMatch(KpiContribution::complete)?"SUCCEEDED":"PARTIAL",
        contributions.size()==1?contributions.get(0):null,now,revision,Math.addExact(attempts,1),null,null,
        contributions.size()==1?null:List.copyOf(contributions));
    var result=ops.updateFirst(sourceQuery(event.id(),event.tenantId()),new Update().set("actualMetrics",state),Submission.class);
    if(!result.wasAcknowledged() || result.getMatchedCount()!=1) throw new KpiProcessingException("SUBMISSION_MARKER_NOT_WRITTEN",true);
  }
  private Query sourceQuery(String id,String tenant) { return Query.query(Criteria.where("_id").is(id).and("tenantId").is(tenant)); }
  private void validateSource(Invoice invoice,Submission event) {
    if(invoice==null) throw new KpiProcessingException("SOURCE_INVOICE_NOT_FOUND",false);
    if(event==null || !Submission.INVOICE_PAID.equals(event.event()) || !Objects.equals(event.id(),invoice.getSubmissionId())
        || !Objects.equals(event.tenantId(),invoice.getTenantId()) || !Objects.equals(event.createdAt(),invoice.getReceivedAt()))
      throw new KpiProcessingException("SUBMISSION_INVOICE_SCOPE_MISMATCH",false);
    if("INVOICE_BATCH".equals(event.sourceType())) {
      if(!event.id().equals(event.sourceId()) || !"PLAN_SALE_BATCH".equals(invoice.getType())) throw new KpiProcessingException("BATCH_SUBMISSION_INVALID",false);
      return;
    }
    if(!"INVOICE".equals(event.sourceType()) || !Objects.equals(event.id(),invoice.getId()+":"+Submission.INVOICE_PAID)
        || !Objects.equals(event.sourceId(),invoice.getId()) || !Objects.equals(event.propositionId(),invoice.getPropositionId())
        || !Objects.equals(event.planId(),invoice.getPlanId()) || !Objects.equals(event.orchestratorId(),invoice.getOrchestratorId())
        || event.orchestratorVersion()!=invoice.getOrchestratorVersion()) throw new KpiProcessingException("SUBMISSION_INVOICE_SCOPE_MISMATCH",false);
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
