package com.ecofood.app.metrics;

import static com.ecofood.app.metrics.MetricsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ecofood.app.metrics.entity.ActualMetrics;
import com.ecofood.app.metrics.service.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ActualMetricsStoreTest {
  private final TransactionalMetricsFixture db = new TransactionalMetricsFixture();
  private final KpiCalculator calculator = new KpiCalculator();
  private final ActualMetricsStore store = new ActualMetricsStore(db.transactions, calculator, 100);

  @Test
  void duplicateDeliveryCountsOnceAndKeepsOneImmutableContributionOnTheExistingSubmission() {
    var invoice = invoice("i", "tenant", "prop", "USD", "100");
    db.add(invoice);
    store.process(invoice.getSubmissionId(), "tenant");
    store.process(invoice.getSubmissionId(), "tenant");
    var result = db.metrics.get(calculator.calculate(invoice).key().id());
    assertEquals(1, result.totalSalesVolume());
    assertEquals(bd("66.00"), result.profit());
    assertEquals("SUCCEEDED", db.states.get(invoice.getSubmissionId()).state());
    assertEquals(1, db.states.size());
    assertTrue(invoice.isSubmissionPending()); // Task 3B does not mutate the Task 3A handoff.
  }

  @Test
  void crashBetweenAggregateAndMarkerRollsBackBothAndRetryIsSafe() {
    var invoice = invoice("i", "tenant", "prop", "USD", null);
    db.add(invoice);
    db.failNextMarker = true;
    var submissionId1 = invoice.getSubmissionId();
    assertThrows(DataAccessResourceFailureException.class, () -> store.process(submissionId1, "tenant"));
    assertTrue(db.metrics.isEmpty());
    assertTrue(db.states.isEmpty());
    store.process(invoice.getSubmissionId(), "tenant");
    store.process(invoice.getSubmissionId(), "tenant");
    assertEquals(1, db.metrics.values().iterator().next().totalSalesVolume());
    assertNull(db.metrics.values().iterator().next().profit());
    assertEquals("PARTIAL", db.states.get(invoice.getSubmissionId()).state());
  }

  @Test
  void concurrentWorkersForSameAndDifferentInvoicesDoNotDoubleCount() throws Exception {
    var first = invoice("i1", "tenant", "prop", "USD", "100");
    var second = invoice("i2", "tenant", "prop", "USD", "100");
    db.add(first); db.add(second);
    runTogether(() -> store.process(first.getSubmissionId(), "tenant"),
        () -> store.process(first.getSubmissionId(), "tenant"),
        () -> store.process(second.getSubmissionId(), "tenant"));
    var result = db.metrics.values().iterator().next();
    assertEquals(2, result.totalSalesVolume());
    assertEquals(bd("132.00"), result.profit());
    assertEquals(2, db.states.size());
  }

  @Test
  void separatesTenantPropositionAndCurrencyWithoutWritingPropositionTotals() {
    var rows = List.of(invoice("i1", "tenant", "prop", "USD", null),
        invoice("i2", "other", "prop", "USD", null),
        invoice("i3", "tenant", "other", "USD", null),
        invoice("i4", "tenant", "prop", "VND", null));
    for (var invoice : rows) {
      db.add(invoice);
      store.process(invoice.getSubmissionId(), invoice.getTenantId());
    }
    assertEquals(4, db.metrics.size());
    assertTrue(db.metrics.values().stream().allMatch(row -> row.totalSalesVolume() == 1));
  }

  @Test
  void invalidEventTenantOrInvoicePaymentCannotContribute() {
    var invoice = invoice("i", "tenant", "prop", "USD", null);
    db.add(invoice);
    var submissionId2 = invoice.getSubmissionId();
    assertThrows(KpiProcessingException.class, () -> store.process(submissionId2, "other"));
    invoice.setPaymentStatus(null);
    var submissionId3 = invoice.getSubmissionId();
    assertThrows(KpiProcessingException.class, () -> store.process(submissionId3, "tenant"));
    assertTrue(db.metrics.isEmpty());
    assertTrue(db.states.isEmpty());
  }

  @Test
  void sourceChangesAfterProjectionAreBlockedRatherThanAddingTheInvoiceAgain() {
    var invoice = invoice("i", "tenant", "prop", "USD", "100");
    db.add(invoice);
    store.process(invoice.getSubmissionId(), "tenant");
    var original = db.metrics.values().iterator().next();
    invoice.setSnapshot(snapshot(invoice, bd("500"), invoice.getSnapshot().costing(), bd("25"), "tenant", List.of()));
    var submissionId4 = invoice.getSubmissionId();
    var failure = assertThrows(KpiProcessingException.class,
        () -> store.process(submissionId4, "tenant"));
    assertEquals("PROJECTED_INVOICE_INPUT_CHANGED", failure.code());
    assertEquals(original, db.metrics.values().iterator().next());
    assertFalse(store.reconcile(original.key()).consistent());
  }

  @Test
  void reconciliationDetectsMissingOrWrongProjectionAndExplicitRebuildIsIdempotent() {
    var first = invoice("i1", "tenant", "prop", "USD", "100");
    var second = invoice("i2", "tenant", "prop", "USD", null);
    db.add(first); db.add(second);
    var key = calculator.calculate(first).key();
    assertFalse(store.reconcile(key).consistent());
    var repaired = store.rebuild(key);
    assertTrue(repaired.consistent());
    assertEquals(2, repaired.actual().totalSalesVolume());
    assertNull(repaired.actual().profit());
    assertEquals(bd("66.00"), repaired.actual().knownTotals().profit());
    assertTrue(store.reconcile(key).consistent());
    store.process(first.getSubmissionId(), "tenant");
    store.process(second.getSubmissionId(), "tenant");
    assertEquals(2, db.metrics.get(key.id()).totalSalesVolume());
    db.metrics.put(key.id(), ActualMetrics.empty(key, KpiCalculator.VERSION, Instant.now()));
    assertFalse(store.reconcile(key).consistent());
    assertEquals(2, store.rebuild(key).actual().totalSalesVolume());
    assertEquals(2, store.rebuild(key).actual().totalSalesVolume());
  }

  @Test
  void rebuildAndProcessorShareTheSameAtomicBoundary() throws Exception {
    var first = invoice("i1", "tenant", "prop", "USD", "100");
    var second = invoice("i2", "tenant", "prop", "USD", "100");
    db.add(first); db.add(second);
    var key = calculator.calculate(first).key();
    store.process(first.getSubmissionId(), "tenant");
    runTogether(() -> store.rebuild(key), () -> store.process(second.getSubmissionId(), "tenant"));
    assertTrue(store.reconcile(key).consistent());
    assertEquals(2, db.metrics.get(key.id()).totalSalesVolume());
    assertEquals(bd("132.00"), db.metrics.get(key.id()).profit());
  }

  @Test
  void rebuildCanMaterializeTheSameDurableEventIfTask3AHandoffWasInterrupted() {
    var invoice = invoice("i", "tenant", "prop", "USD", null);
    db.add(invoice);
    db.events.clear();
    var key = calculator.calculate(invoice).key();
    assertFalse(store.reconcile(key).consistent());
    store.rebuild(key);
    assertEquals(invoice.getSubmissionHandoff(), db.events.get(invoice.getSubmissionId()));
    assertTrue(store.reconcile(key).consistent());
    assertEquals(1, db.events.size());
    assertTrue(invoice.isSubmissionPending());
  }

  @Test
  void boundedRebuildAbortsWithoutPartialWritesWhenBucketIsTooLarge() {
    var first = invoice("i1", "tenant", "prop", "USD", null);
    var second = invoice("i2", "tenant", "prop", "USD", null);
    db.add(first); db.add(second);
    var limited = new ActualMetricsStore(db.transactions, calculator, 1);
    var metricsKey1 = calculator.calculate(first).key();
    assertThrows(KpiProcessingException.class, () -> limited.rebuild(metricsKey1));
    assertTrue(db.metrics.isEmpty());
    assertTrue(db.states.isEmpty());
  }

  @Test
  void failedRebuildRollsBackMarkersAndPreservesThePreviouslyCommittedProjection() {
    var first = invoice("i1", "tenant", "prop", "USD", "100");
    var second = invoice("i2", "tenant", "prop", "USD", null);
    db.add(first);
    store.process(first.getSubmissionId(), "tenant");
    var key = calculator.calculate(first).key();
    var before = db.metrics.get(key.id());
    var markerBefore = db.states.get(first.getSubmissionId());
    db.add(second);
    db.failNextMarker = true;
    assertThrows(DataAccessResourceFailureException.class, () -> store.rebuild(key));
    assertEquals(before, db.metrics.get(key.id()));
    assertEquals(markerBefore, db.states.get(first.getSubmissionId()));
    assertFalse(db.states.containsKey(second.getSubmissionId()));
    assertEquals(2, store.rebuild(key).actual().totalSalesVolume());
  }

  @Test
  void processorAndReconciliationUseTheSameVietnamMonthBounds() {
    var before = invoice("i1", "tenant", "prop", "USD", null);
    var after = invoice("i2", "tenant", "prop", "USD", null);
    before.setReceivedAt(Instant.parse("2026-12-31T16:59:59.999Z"));
    after.setReceivedAt(Instant.parse("2026-12-31T17:00:00Z"));
    refreshHandoff(before); refreshHandoff(after);
    db.add(before); db.add(after);
    store.process(before.getSubmissionId(), "tenant");
    store.process(after.getSubmissionId(), "tenant");
    var december = calculator.calculate(before).key();
    var january = calculator.calculate(after).key();
    assertEquals(2026, december.year());
    assertEquals(2027, january.year());
    assertEquals(2, db.metrics.size());
    assertTrue(store.reconcile(december).consistent());
    assertTrue(store.reconcile(january).consistent());
    assertEquals(1, store.rebuild(december).actual().totalSalesVolume());
    assertEquals(1, store.rebuild(january).actual().totalSalesVolume());
  }

  private void runTogether(Runnable... actions) throws Exception {
    var pool = Executors.newFixedThreadPool(actions.length);
    var ready = new CountDownLatch(actions.length);
    var start = new CountDownLatch(1);
    try {
      var futures = new java.util.ArrayList<Future<?>>();
      for (var action : actions) futures.add(pool.submit(() -> {
        ready.countDown();
        try { start.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
        action.run();
      }));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (var future : futures) future.get(10, TimeUnit.SECONDS);
    } finally { start.countDown(); pool.shutdownNow(); }
  }
}
