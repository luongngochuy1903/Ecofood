package com.ecofood.app.metrics;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.metrics.entity.*;
import com.ecofood.app.metrics.service.MongoMetricsTransactions;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.submission.entity.InvoiceBatchSubmission;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.enums.TransactionStatus;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.bson.Document;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Unit-test atomic boundary with rollback. This does not simulate a real MongoDB deployment. */
final class TransactionalMetricsFixture {
  final MongoMetricsTransactions transactions = mock(MongoMetricsTransactions.class);
  final MongoOperations ops = mock(MongoOperations.class);
  final Map<String, Invoice> invoices = new HashMap<>();
  final Map<String, Submission> events = new HashMap<>();
  final Map<String, InvoiceBatchSubmission> batches = new HashMap<>();
  final Map<String, ActualMetrics> metrics = new HashMap<>();
  final Map<String, SubmissionMetricsState.Processing> states = new HashMap<>();
  final ReentrantLock lock = new ReentrantLock();
  boolean failNextMarker;

  @SuppressWarnings("unchecked")
  TransactionalMetricsFixture() {
    doAnswer(call -> {
      lock.lock();
      var previousMetrics = new HashMap<>(metrics);
      var previousStates = new HashMap<>(states);
      var previousEvents = new HashMap<>(events);
      try { return ((Function<MongoOperations, Object>) call.getArgument(0)).apply(ops); }
      catch (RuntimeException failure) {
        metrics.clear(); metrics.putAll(previousMetrics);
        states.clear(); states.putAll(previousStates);
        events.clear(); events.putAll(previousEvents);
        throw failure;
      } finally { lock.unlock(); }
    }).when(transactions).execute(any());

    when(ops.findOne(any(Query.class), eq(Submission.class))).thenAnswer(call -> {
      var query = ((Query) call.getArgument(0)).getQueryObject();
      var row = events.get(query.getString("_id"));
      return row != null && Objects.equals(query.getString("tenantId"), row.tenantId()) ? row : null;
    });
    when(ops.findOne(any(Query.class), eq(Invoice.class))).thenAnswer(call -> {
      var query = ((Query) call.getArgument(0)).getQueryObject();
      var row = invoices.get(query.getString("_id"));
      return row != null && Objects.equals(query.getString("tenantId"), row.getTenantId()) ? row : null;
    });
    when(ops.findOne(any(Query.class), eq(SubmissionMetricsState.class))).thenAnswer(call -> {
      var query = ((Query) call.getArgument(0)).getQueryObject();
      var event = events.get(query.getString("_id"));
      return event == null || !Objects.equals(query.getString("tenantId"), event.tenantId()) ? null
          : new SubmissionMetricsState(event.id(), states.get(event.id()));
    });
    when(ops.findOne(any(Query.class),eq(InvoiceBatchSubmission.class))).thenAnswer(call->{
      var query=((Query)call.getArgument(0)).getQueryObject();
      var batch=batches.get(query.getString("_id"));
      return batch!=null && batch.tenantId().equals(query.getString("tenantId"))?batch:null;
    });
    when(ops.findById(anyString(), eq(ActualMetrics.class)))
        .thenAnswer(call -> metrics.get(call.getArgument(0)));
    when(ops.save(any(ActualMetrics.class))).thenAnswer(call -> {
      ActualMetrics row = call.getArgument(0);
      metrics.put(row.id(), row);
      return row;
    });
    when(ops.insert(any(Submission.class))).thenAnswer(call -> {
      Submission row = call.getArgument(0);
      if (events.putIfAbsent(row.id(), row) != null) throw new DuplicateKeyException("event exists");
      return row;
    });
    when(ops.updateFirst(any(Query.class), any(Update.class), eq(Submission.class))).thenAnswer(call -> {
      if (failNextMarker) {
        failNextMarker = false;
        throw new DataAccessResourceFailureException("failure after aggregate write");
      }
      var query = ((Query) call.getArgument(0)).getQueryObject();
      var event = events.get(query.getString("_id"));
      if (event == null || !Objects.equals(query.getString("tenantId"), event.tenantId()))
        return UpdateResult.acknowledged(0, 0L, null);
      var value = ((Update) call.getArgument(1)).getUpdateObject().get("$set", Document.class)
          .get("actualMetrics");
      states.put(event.id(), (SubmissionMetricsState.Processing) value);
      return UpdateResult.acknowledged(1, 1L, null);
    });
    when(ops.find(any(Query.class), eq(Invoice.class))).thenAnswer(call -> {
      Query query = call.getArgument(0);
      var criteria = query.getQueryObject();
      var dates = criteria.get("receivedAt", Document.class);
      Instant start = (Instant) dates.get("$gte"), end = (Instant) dates.get("$lt");
      return invoices.values().stream()
          .filter(row -> Objects.equals(criteria.getString("tenantId"), row.getTenantId()))
          .filter(row -> {
            var alternatives=criteria.getList("$or",Document.class);
            String proposition=alternatives==null?criteria.getString("propositionId"):alternatives.get(0).getString("propositionId");
            return Objects.equals(proposition,row.getPropositionId()) || row.getPlanLines()!=null
                && row.getPlanLines().stream().anyMatch(line->Objects.equals(proposition,line.snapshot().propositionId()));
          })
          .filter(row -> Objects.equals(criteria.getString("currency"), row.getCurrency()))
          .filter(row -> row.getStatus() == TransactionStatus.ACCEPTED)
          .filter(row -> !row.getReceivedAt().isBefore(start) && row.getReceivedAt().isBefore(end))
          .sorted(Comparator.comparing(Invoice::getId)).limit(query.getLimit()).toList();
    });
  }

  void add(Invoice invoice) {
    invoices.put(invoice.getId(), invoice);
    events.put(invoice.getSubmissionId(), invoice.getSubmissionHandoff());
  }
}
