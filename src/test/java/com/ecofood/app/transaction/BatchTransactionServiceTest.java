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
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.dao.DataAccessResourceFailureException;

class BatchTransactionServiceTest {
  private final MongoMetricsTransactions transactions=mock(MongoMetricsTransactions.class);
  private final MongoOperations ops=mock(MongoOperations.class);
  private final BatchSalePlanResolver plans=mock(BatchSalePlanResolver.class);
  private final SaleConfirmationHandoff confirmations=mock(SaleConfirmationHandoff.class);
  private final jakarta.validation.ValidatorFactory validation=Validation.buildDefaultValidatorFactory();
  private final BatchTransactionService service=new BatchTransactionService(transactions,plans,confirmations,validation.getValidator());
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
    when(ops.findOne(any(Query.class),eq(Invoice.class))).thenAnswer(call->{var q=((Query)call.getArgument(0)).getQueryObject(); return invoices.values().stream().filter(i->i.getTenantId().equals(q.getString("tenantId")) && i.getPosId().equals(q.getString("posId")) && i.getInvoiceNumber().equals(q.getString("invoiceNumber"))).findFirst().orElse(null);});
    when(ops.insert(any(Invoice.class))).thenAnswer(call->{Invoice row=call.getArgument(0);invoices.put(row.getId(),row);return row;});
    when(ops.insert(any(InvoiceBatchSubmission.class))).thenAnswer(call->{if(failEvent){failEvent=false;throw new DataAccessResourceFailureException("write failed");} InvoiceBatchSubmission event=call.getArgument(0);events.put(event.id(),event);return event;});
    when(plans.resolve(anyString(),anyString(),anyString(),any(Item.class),any())).thenAnswer(call->{
      Item item=call.getArgument(3); String currency=item.planId().equals("USD")?"USD":"VND";
      var snapshot=new InvoiceSnapshot(1,"prop",1L,call.getArgument(0),call.getArgument(0),item.planId(),1,item.orchestratorId(),1,"product","process",1,1L,null,null,null,currency,call.getArgument(4),"Asia/Ho_Chi_Minh",11,2026,"MISSING_DATA",List.of("NET_REVENUE_UNVERIFIED"),null,null,null);
      return new InvoicePlanLine(item.lineId(),item.orchestratorId(),item.planId(),"Plan",item.quantity().longValueExact(),item.unitPrice(),item.amount(),snapshot,false);
    });
  }
  @AfterEach void close(){validation.close();}
  private Item item(String id,String plan,long quantity){return new Item(id,"orch",plan,quantity,new BigDecimal("50000"),new BigDecimal("50000").multiply(BigDecimal.valueOf(quantity)));}
  private Request request(Receipt... invoices){return new Request(new Transaction(List.of(invoices)));}
  @Test void oneBatchCreatesTwoInvoicesButOnlyOneSharedSubmission() {
    var result=service.submit("tenant","seller",request(new Receipt("INV-1",List.of(item("1","A",1),item("2","B",2))),new Receipt("INV-2",List.of(item("1","A",1)))));
    assertEquals(2,invoices.size());assertEquals(1,events.size());
    var event=events.values().iterator().next();assertEquals(2,event.invoiceIds().size());
    assertTrue(invoices.values().stream().allMatch(i->event.id().equals(i.getSubmissionId()) && i.getPlanId()==null && "PAID".equals(i.getPaymentStatus())));
    assertTrue(result.transaction().invoices().stream().allMatch(r->r.result().equals("ACCEPTED")));
    assertTrue(invoices.values().stream().anyMatch(i->i.getTotalAmount().compareTo(new BigDecimal("150000"))==0));
    verify(confirmations).deliver(event);
  }
  @Test void retryAndReorderedBatchDoNotCreateAnotherSubmissionOrReadNewConfiguration() {
    var a=new Receipt("INV-1",List.of(item("1","A",1)));var b=new Receipt("INV-2",List.of(item("1","A",2)));
    service.submit("tenant","seller",request(a,b)); clearInvocations(plans);
    service.submit("tenant","seller",request(b,a)); assertEquals(2,invoices.size());assertEquals(1,events.size());verifyNoInteractions(plans);
    var changed=request(new Receipt("INV-1",List.of(item("1","A",3))),b);
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.submit("tenant","seller",changed));
  }
  @Test void sameNumberInAnotherBatchIsRejectedRatherThanCountedAgain() {
    var a=new Receipt("INV-1",List.of(item("1","A",1))); service.submit("tenant","seller",request(a));
    var result=service.submit("tenant","seller",request(a,new Receipt("INV-2",List.of(item("1","A",1)))));
    assertEquals("REJECTED",result.transaction().invoices().get(0).result()); assertEquals(2,invoices.size());
    assertEquals(2,events.size()); assertTrue(events.values().stream().allMatch(e->e.invoiceIds().size()==1));
  }
  @Test void invalidInvoiceIsRejectedWhileOtherInvoiceIsAccepted() {
    var bad=new Item("1","orch","A",2,BigDecimal.TEN,BigDecimal.ONE);
    var response=service.submit("tenant","seller",request(new Receipt("BAD",List.of(bad)),new Receipt("GOOD",List.of(item("1","A",1)))));
    assertEquals("REJECTED",response.transaction().invoices().get(0).result());assertEquals(1,invoices.size());assertEquals(1,events.size());
  }
  @Test void duplicateLineMixedCurrenciesOrScopeViolationCannotProduceAnInvoice() {
    var response=service.submit("tenant","seller",request(new Receipt("DUP",List.of(item("1","A",1),item("1","B",1))),new Receipt("MIX",List.of(item("1","A",1),item("2","USD",1)))));
    assertTrue(response.transaction().invoices().stream().allMatch(r->r.result().equals("REJECTED")));assertTrue(invoices.isEmpty());assertTrue(events.isEmpty());verifyNoInteractions(confirmations);
    when(plans.resolve(eq("other"),anyString(),anyString(),any(),any())).thenThrow(new ForbiddenException("denied"));
    assertEquals("REJECTED",service.submit("other","seller",request(new Receipt("INV",List.of(item("1","A",1))))).transaction().invoices().get(0).result());
  }
  @Test void eventInsertFailureRollsBackEveryInvoiceAndCanBeRetried() {
    var request=request(new Receipt("INV-1",List.of(item("1","A",1))),new Receipt("INV-2",List.of(item("1","A",2)))); failEvent=true;
    assertThrows(DataAccessResourceFailureException.class,()->service.submit("tenant","seller",request));assertTrue(invoices.isEmpty());assertTrue(events.isEmpty());verifyNoInteractions(confirmations);
    service.submit("tenant","seller",request);assertEquals(2,invoices.size());assertEquals(1,events.size());
  }
  @Test void concurrentIdenticalSubmitsCommitOnlyOneBatch() throws Exception {
    var request=request(new Receipt("INV-1",List.of(item("1","A",1))));var pool=Executors.newFixedThreadPool(2);
    try { for(var result:pool.invokeAll(List.<Callable<Response>>of(()->service.submit("tenant","seller",request),()->service.submit("tenant","seller",request)))) result.get(); }
    finally{pool.shutdownNow();}
    assertEquals(1,invoices.size());assertEquals(1,events.size());
  }
}
