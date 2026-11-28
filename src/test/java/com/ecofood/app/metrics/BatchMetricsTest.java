package com.ecofood.app.metrics;

import static com.ecofood.app.metrics.MetricsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import com.ecofood.app.metrics.service.*;
import com.ecofood.app.submission.entity.InvoiceBatchSubmission;
import com.ecofood.app.transaction.entity.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BatchMetricsTest {
  private final TransactionalMetricsFixture db=new TransactionalMetricsFixture();
  private final KpiCalculator calculator=new KpiCalculator();
  private final ActualMetricsStore store=new ActualMetricsStore(db.transactions,calculator,100);
  private Invoice invoiceWithLines(String id,String currency,boolean verified,String... props) {
    var invoice=invoice(id,"tenant",props[0],currency,"100");
    var lines=new ArrayList<InvoicePlanLine>(); int index=0;
    for(String prop:props) {
      var unit=invoice(id,"tenant",prop,currency,"100");
      lines.add(new InvoicePlanLine(""+(++index),"orch","plan","Plan",index,bd("100"),bd("100").multiply(bd(""+index)),unit.getSnapshot(),verified,null));
    }
    invoice.setPlanLines(lines); invoice.setPropositionId(null);
    invoice.setPlanId(null); invoice.setOrchestratorId(null); invoice.setOrchestratorVersion(0); invoice.setSnapshot(null);
    return invoice;
  }
  private InvoiceBatchSubmission batch(Invoice... invoices) {
    var event=InvoiceBatchSubmission.create("batch",invoices[0].getReceivedAt(),"tenant",Arrays.stream(invoices).map(Invoice::getId).toList(),"fingerprint","seller",List.of());
    for(var invoice:invoices) { invoice.setSubmissionId(event.id()); invoice.setSubmittedBy("seller"); db.invoices.put(invoice.getId(),invoice); }
    db.events.put(event.id(),event.envelope()); db.batches.put(event.id(),event); return event;
  }
  @Test void oneSubmissionIncludesMultipleInvoicesPlansQuantitiesAndPropositionsWithoutDuplicates() {
    var first=invoiceWithLines("i1","USD",true,"p1","p2"); var second=invoiceWithLines("i2","USD",true,"p1");
    var event=batch(first,second); store.process(event.id(),"tenant"); store.process(event.id(),"tenant");
    assertEquals(2,db.metrics.size()); assertEquals(4,db.metrics.values().stream().mapToLong(r->r.totalSalesVolume()).sum());
    assertEquals(1,db.states.size()); assertEquals(3,db.states.get(event.id()).entries().size());
    assertTrue(db.metrics.values().stream().allMatch(r->r.profit()!=null));
  }
  @Test void unverifiedSaleConfigurationNeverGeneratesMonetaryKpis() {
    var invoice=invoiceWithLines("i","USD",false,"p1","p2"); batch(invoice);
    store.process("batch","tenant");
    assertEquals(3,db.metrics.values().stream().mapToLong(r->r.totalSalesVolume()).sum());
    assertTrue(db.metrics.values().stream().allMatch(r->r.profit()==null && r.platformCost()==null && r.ingredientCost()==null));
    assertTrue(db.states.get("batch").entries().stream().allMatch(c->c.missingData().contains("SALE_CONFIGURATION_TIME_UNVERIFIED")));
  }
  @Test void failureBeforeSharedMarkerRollsBackAllPropositionBucketsThenRecovers() {
    batch(invoiceWithLines("i","USD",true,"p1","p2")); db.failNextMarker=true;
    assertThrows(RuntimeException.class,()->store.process("batch","tenant")); assertTrue(db.metrics.isEmpty()); assertTrue(db.states.isEmpty());
    store.process("batch","tenant"); assertEquals(2,db.metrics.size());
  }
  @Test void rebuildingOneBucketRebuildsConnectedBatchBucketsAndIsIdempotent() {
    var invoice=invoiceWithLines("i","USD",true,"p1","p2"); batch(invoice);
    var keys=calculator.calculateAll(invoice).stream().map(c->c.key()).toList();
    assertTrue(store.rebuild(keys.get(0)).consistent()); assertEquals(2,db.metrics.size()); assertEquals(1,db.states.size());
    store.process("batch","tenant"); store.rebuild(keys.get(1));
    assertEquals(3,db.metrics.values().stream().mapToLong(r->r.totalSalesVolume()).sum());
    assertTrue(store.reconcile(keys.get(0)).consistent()); assertTrue(store.reconcile(keys.get(1)).consistent());
  }
  @Test void concurrentProcessorsAndRebuildCannotDoubleCount() throws Exception {
    var invoice=invoiceWithLines("i","USD",true,"p1","p2"); batch(invoice); var key=calculator.calculateAll(invoice).get(0).key();
    var pool=Executors.newFixedThreadPool(3);
    try {
      var work=List.<Callable<Void>>of(()->{store.process("batch","tenant");return null;},()->{store.process("batch","tenant");return null;},()->{store.rebuild(key);return null;});
      for(var result:pool.invokeAll(work)) result.get();
    } finally { pool.shutdownNow(); }
    assertEquals(3,db.metrics.values().stream().mapToLong(r->r.totalSalesVolume()).sum()); assertTrue(store.reconcile(key).consistent());
  }
  @Test void currenciesStaySeparateAndTenantCannotReadAnotherBatch() {
    batch(invoiceWithLines("i1","USD",true,"p1"),invoiceWithLines("i2","VND",true,"p1"));
    assertThrows(KpiProcessingException.class,()->store.process("batch","other"));
    store.process("batch","tenant"); assertEquals(2,db.metrics.size());
  }
}
