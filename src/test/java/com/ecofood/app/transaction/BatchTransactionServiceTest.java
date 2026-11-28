package com.ecofood.app.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.transaction.service.BatchTransactionService;
import com.ecofood.app.transaction.dto.BatchTransactionDtos.*;
import com.ecofood.app.transaction.entity.*;
import com.ecofood.app.submission.entity.InvoiceBatchSubmission;
import com.ecofood.app.metrics.service.MongoMetricsTransactions;
import com.ecofood.app.orchestrator.service.BatchSalePlanResolver;
import com.ecofood.app.notification.service.SaleConfirmationHandoff;
import com.ecofood.app.config.*;
import com.ecofood.app.proposition.service.PropositionSalesMetrics;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.dao.DataAccessResourceFailureException;

class BatchTransactionServiceTest {
  private final MongoMetricsTransactions transactions=mock(MongoMetricsTransactions.class);
  private final MongoOperations ops=mock(MongoOperations.class);
  private final BatchSalePlanResolver plans=mock(BatchSalePlanResolver.class);
  private final SaleConfirmationHandoff confirmations=mock(SaleConfirmationHandoff.class);
  private final PropositionSalesMetrics metrics=mock(PropositionSalesMetrics.class);
  private final jakarta.validation.ValidatorFactory validation=Validation.buildDefaultValidatorFactory();
  private final BatchTransactionService service=new BatchTransactionService(transactions,plans,confirmations,metrics,validation.getValidator());
  private final Map<String,Invoice> invoices=new HashMap<>();
  private final Map<String,InvoiceBatchSubmission> events=new HashMap<>();
  private boolean failEvent;
  @BeforeEach @SuppressWarnings("unchecked") void setup() {
    var lock=new ReentrantLock();
    when(transactions.execute(any())).thenAnswer(call->{
      lock.lock(); var oldInvoices=new HashMap<>(invoices); var oldEvents=new HashMap<>(events);
      try { return ((Function<MongoOperations,Object>)call.getArgument(0)).apply(ops); }
      catch(RuntimeException failure) { invoices.clear();invoices.putAll(oldInvoices);events.clear();events.putAll(oldEvents);throw failure; }
      finally { lock.unlock(); }
    });
    when(ops.findOne(any(Query.class),eq(InvoiceBatchSubmission.class))).thenAnswer(call->{var q=((Query)call.getArgument(0)).getQueryObject(); return events.get(q.getString("_id"));});
    when(ops.insert(any(Invoice.class))).thenAnswer(call->{Invoice row=call.getArgument(0);invoices.put(row.getId(),row);return row;});
    when(ops.insert(any(InvoiceBatchSubmission.class))).thenAnswer(call->{if(failEvent){failEvent=false;throw new DataAccessResourceFailureException("write failed");} InvoiceBatchSubmission event=call.getArgument(0);events.put(event.id(),event);return event;});
    when(plans.resolve(anyString(),anyString(),anyInt(),any(Item.class),any())).thenAnswer(call->{
      String tenant=call.getArgument(0); int index=call.getArgument(2); Item item=call.getArgument(3); Instant receivedAt=call.getArgument(4);
      String currency=item.planId().equals("USD")?"USD":"VND";
      var unitPrice=new BigDecimal("50000"); var amount=unitPrice.multiply(item.quantity());
      var snapshot=new InvoiceSnapshot(1,"prop",1L,tenant,tenant,item.planId(),1,"orch",1,"product","process",1,1L,null,null,null,currency,receivedAt,"Asia/Ho_Chi_Minh",11,2026,"MISSING_DATA",List.of("NET_REVENUE_UNVERIFIED"),null,null,null);
      return new InvoicePlanLine("item-"+index,"orch",item.planId(),"Plan",item.quantity().longValueExact(),unitPrice,amount,snapshot,item.actualPrice()!=null,item.additionalInfo());
    });
  }
  @AfterEach void close(){validation.close();}
  private Item item(String plan,long quantity){return new Item(plan,quantity,null);}
  private Request request(Receipt... invoices){return new Request(new Transaction(List.of(invoices)));}
  @Test void oneBatchCreatesTwoInvoicesButOnlyOneSharedSubmission() {
    var result=service.submit("tenant","seller",request(new Receipt(null,List.of(item("A",1),item("B",2))),new Receipt(null,List.of(item("A",1)))));
    assertEquals(2,invoices.size());assertEquals(1,events.size());
    var event=events.values().iterator().next();assertEquals(2,event.invoiceIds().size());
    assertTrue(invoices.values().stream().allMatch(i->event.id().equals(i.getSubmissionId()) && i.getPlanId()==null && i.isBatch()));
    assertTrue(result.transaction().invoices().stream().allMatch(r->r.result().equals("ACCEPTED")));
    assertTrue(invoices.values().stream().anyMatch(i->i.getTotalAmount().compareTo(new BigDecimal("150000"))==0));
    verify(confirmations).deliver(event);
  }
  @Test void identicalRetrySubmitDoesNotCreateAnotherSubmissionOrReadNewConfiguration() {
    var a=new Receipt(null,List.of(item("A",1)));var b=new Receipt(null,List.of(item("A",2)));
    service.submit("tenant","seller",request(a,b)); clearInvocations(plans);
    service.submit("tenant","seller",request(a,b)); assertEquals(2,invoices.size());assertEquals(1,events.size());verifyNoInteractions(plans);
  }
  @Test void changedContentIsTreatedAsABrandNewBatchRatherThanAConflict() {
    var a=new Receipt(null,List.of(item("A",1)));var b=new Receipt(null,List.of(item("A",2)));
    service.submit("tenant","seller",request(a,b));
    var changed=request(new Receipt(null,List.of(item("A",3))),b);
    service.submit("tenant","seller",changed);
    assertEquals(4,invoices.size());assertEquals(2,events.size());
  }
  @Test void invalidInvoiceIsRejectedWhileOtherInvoiceIsAccepted() {
    when(plans.resolve(anyString(),anyString(),anyInt(),argThat(i->i!=null&&"BAD".equals(i.planId())),any()))
        .thenThrow(new BadRequestException("Plan has no defined selling price"));
    var response=service.submit("tenant","seller",request(new Receipt(null,List.of(item("BAD",2))),new Receipt(null,List.of(item("A",1)))));
    assertEquals("REJECTED",response.transaction().invoices().get(0).result());assertEquals(1,invoices.size());assertEquals(1,events.size());
  }
  @Test void mixedCurrencyReceiptAndScopeViolationCannotProduceAnInvoice() {
    var response=service.submit("tenant","seller",request(new Receipt(null,List.of(item("A",1),item("USD",1)))));
    assertEquals("REJECTED",response.transaction().invoices().get(0).result());assertTrue(invoices.isEmpty());assertTrue(events.isEmpty());verifyNoInteractions(confirmations);
    when(plans.resolve(eq("other"),anyString(),anyInt(),any(Item.class),any())).thenThrow(new ForbiddenException("denied"));
    assertEquals("REJECTED",service.submit("other","seller",request(new Receipt(null,List.of(item("A",1))))).transaction().invoices().get(0).result());
  }
  @Test void eventInsertFailureRollsBackEveryInvoiceAndCanBeRetried() {
    var request=request(new Receipt(null,List.of(item("A",1))),new Receipt(null,List.of(item("A",2)))); failEvent=true;
    assertThrows(DataAccessResourceFailureException.class,()->service.submit("tenant","seller",request));assertTrue(invoices.isEmpty());assertTrue(events.isEmpty());verifyNoInteractions(confirmations);
    service.submit("tenant","seller",request);assertEquals(2,invoices.size());assertEquals(1,events.size());
  }
  @Test void concurrentIdenticalSubmitsCommitOnlyOneBatch() throws Exception {
    var request=request(new Receipt(null,List.of(item("A",1))));var pool=Executors.newFixedThreadPool(2);
    try { for(var result:pool.invokeAll(List.<Callable<Response>>of(()->service.submit("tenant","seller",request),()->service.submit("tenant","seller",request)))) result.get(); }
    finally{pool.shutdownNow();}
    assertEquals(1,invoices.size());assertEquals(1,events.size());
  }
  @Test void receiptMissingRequiredInvoiceHolderFieldIsRejected() {
    doThrow(new BadRequestException("Missing required invoiceHolder field: email"))
        .when(plans).requireInvoiceHolder(eq("orch"), eq(Map.of()));
    var response=service.submit("tenant","seller",request(new Receipt(Map.of(),List.of(item("A",1)))));
    assertEquals("REJECTED",response.transaction().invoices().get(0).result());
    assertTrue(invoices.isEmpty());assertTrue(events.isEmpty());
  }
  @Test void receiptWithRequiredInvoiceHolderFieldPresentIsAccepted() {
    var response=service.submit("tenant","seller",request(new Receipt(Map.of("email","a@b.com"),List.of(item("A",1)))));
    assertEquals("ACCEPTED",response.transaction().invoices().get(0).result());
    verify(plans).requireInvoiceHolder("orch", Map.of("email","a@b.com"));
  }
  @Test void acceptedInvoiceIsLinkedToTheMatchedCustomerWhenOneIsFound() {
    when(plans.matchCustomer("orch", Map.of("email", "a@b.com"))).thenReturn(Optional.of("customer-1"));
    service.submit("tenant","seller",request(new Receipt(Map.of("email","a@b.com"),List.of(item("A",1)))));
    var invoice = invoices.values().iterator().next();
    assertEquals("customer-1", invoice.getInvoiceHolder().customerId());
    assertNull(invoice.getInvoiceHolder().userId());
  }
  @Test void acceptedInvoiceStaysUnlinkedWhenNoCustomerMatches() {
    service.submit("tenant","seller",request(new Receipt(Map.of("email","a@b.com"),List.of(item("A",1)))));
    assertNull(invoices.values().iterator().next().getInvoiceHolder());
  }
  @Test void acceptedInvoicePersistsTheReceiptsInvoiceHolderAsOrchestratorPayload() {
    service.submit("tenant","seller",request(new Receipt(Map.of("email","a@b.com"),List.of(item("A",1)))));
    assertEquals(Map.of("email","a@b.com"),invoices.values().iterator().next().getOrchestratorPayload());
  }
  @Test void acceptedInvoicesAndMetricsUseTheSameMongoTransaction() {
    service.submit("tenant","seller",request(new Receipt(null,List.of(item("A",2)))));
    verify(metrics).record(eq(ops), argThat(rows -> rows.size() == 1
        && rows.get(0).getTotalAmount().compareTo(new BigDecimal("100000")) == 0));
  }
  @Test void identicalRetrySubmitDoesNotRecordActualMetricsAgain() {
    service.submit("tenant","seller",request(new Receipt(null,List.of(item("A",1)))));
    clearInvocations(metrics);
    service.submit("tenant","seller",request(new Receipt(null,List.of(item("A",1)))));
    verifyNoInteractions(metrics);
  }
  @Test void metricsFailureRollsBackTheBatchInsteadOfReturningAcceptedWithMissingActuals() {
    doThrow(new IllegalStateException("metrics unavailable")).when(metrics).record(eq(ops), anyList());
    assertThrows(IllegalStateException.class,
        () -> service.submit("tenant", "seller", request(new Receipt(null, List.of(item("A", 1))))));
    assertTrue(invoices.isEmpty());
    assertTrue(events.isEmpty());
    verifyNoInteractions(confirmations);
  }
}
