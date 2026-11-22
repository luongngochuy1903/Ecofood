package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.complaint.entity.Complaint;
import com.ecofood.app.complaint.security.ComplaintAccess;
import com.ecofood.app.complaint.service.ComplaintHandoff;
import com.ecofood.app.complaint.service.ComplaintServiceImpl;
import com.ecofood.app.customer.security.CustomerAccess;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.client.CoreCustomerInvoiceClient;
import com.ecofood.core.dto.ComplaintDtos;
import com.ecofood.core.enums.ComplaintStatusEnum;
import com.ecofood.core.enums.ComplaintTypeEnum;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ComplaintServiceTest {
  static final String TENANT="111111111111111111111111", ECO="222222222222222222222222", USER="333333333333333333333333", ADMIN="444444444444444444444444", OTHER="555555555555555555555555";
  AtomicComplaintMongo db; CustomerAccess customers; CoreCustomerInvoiceClient invoices; ComplaintAccess admins; ComplaintHandoff handoff; ComplaintServiceImpl service;
  @BeforeEach void setup() {
    db=new AtomicComplaintMongo(); customers=mock(CustomerAccess.class); invoices=mock(CoreCustomerInvoiceClient.class); admins=mock(ComplaintAccess.class);
    handoff=new ComplaintHandoff(db.mongo); service=new ComplaintServiceImpl(db.mongo,customers,invoices,admins,handoff,mock(NotificationDirectory.class));
    when(customers.current()).thenReturn(new CustomerAccess.Actor(USER,TENANT,null));
    when(admins.ecosystemTenant()).thenReturn(ECO);
    when(admins.current()).thenReturn(new ComplaintAccess.Actor(ADMIN,TENANT,List.of(ComplaintTypeEnum.TENANT),true,true));
    var invoice=new Invoice(); invoice.setId("invoice"); invoice.setTenantId(TENANT); invoice.setInvoiceHolder(new Invoice.InvoiceHolder(USER,USER));
    when(invoices.requireOwned(any(),eq("invoice"))).thenReturn(invoice);
  }
  ComplaintDtos.Create input(ComplaintTypeEnum type) { return new ComplaintDtos.Create(type,type==ComplaintTypeEnum.TENANT?"invoice":null,"Subject","Description",UUID.randomUUID().toString()); }
  Complaint submit() { return service.create(input(ComplaintTypeEnum.TENANT)); }
  Complaint action(Complaint c,String action,ComplaintStatusEnum next) { return service.action(c.getId(),action,new ComplaintDtos.Action(c.getRevision(),"Action note",next,null)); }
  @Test void tenantUsesOwnedInvoiceRouteAndDoesNotWriteInvoiceOrMetrics() {
    var c=submit(); assertEquals(TENANT,c.getTenantId()); assertEquals(USER,c.getCustomerId()); assertEquals("TENANT_ADMIN",c.getQueue().role());
    assertEquals(ComplaintStatusEnum.SUBMITTED,c.getStatus()); assertEquals(1,c.getEvents().size()); assertEquals("CREATED",c.getEvents().get(0).eventType());
    assertEquals(Set.of("complaint","submission"),db.collections.keySet());
    verify(invoices).requireOwned(argThat(a->a.userId().equals(USER)&&a.tenantId().equals(TENANT)),eq("invoice"));
    verifyNoMoreInteractions(invoices);
  }
  @Test void ecosystemNeedsNoInvoiceOrTransactionAndRoutesOnlyToExistingEco() {
    var c=service.create(input(ComplaintTypeEnum.ECOSYSTEM)); assertNull(c.getTenantId()); assertNull(c.getInvoiceId()); assertEquals(ECO,c.getQueue().tenantId()); assertEquals("ECO_ADMIN",c.getQueue().role()); verifyNoInteractions(invoices);
    assertTrue(service.adminList(null,null,0,20).content().isEmpty());
    when(admins.current()).thenReturn(new ComplaintAccess.Actor(ADMIN,ECO,List.of(ComplaintTypeEnum.ECOSYSTEM),true,true));
    assertEquals(c.getId(),service.adminList(null,null,0,20).content().get(0).getId());
  }
  @Test void missingInvoiceIsRejected() {
    var i=input(ComplaintTypeEnum.TENANT);
    var create1 = new ComplaintDtos.Create(i.complaintType(),null,i.subject(),i.description(),i.requestId());
    assertThrows(ResponseStatusException.class,()-> service.create(create1));
    assertTrue(db.rows(Complaint.class).isEmpty());
  }
  @Test void otherCustomerUnownedAndMissingInvoicesAreRejectedWithoutDisclosure() {
    for(String reference:List.of("other-owner","unowned","missing")) {
      when(invoices.requireOwned(any(),eq(reference))).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Invoice unavailable"));
      var i=input(ComplaintTypeEnum.TENANT);
      var create2 = new ComplaintDtos.Create(i.complaintType(),reference,i.subject(),i.description(),i.requestId());
      var error=assertThrows(ResponseStatusException.class,()-> service.create(create2));
      assertEquals(HttpStatus.NOT_FOUND,error.getStatusCode());
    }
    assertTrue(db.rows(Complaint.class).isEmpty()); assertTrue(db.rows(Submission.class).isEmpty());
  }
  @Test void optionalEcosystemInvoiceStillRequiresOwnership() {
    when(invoices.requireOwned(any(),eq("private"))).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
    var create3 = new ComplaintDtos.Create(ComplaintTypeEnum.ECOSYSTEM,"private","Subject","Description",UUID.randomUUID().toString());
    assertThrows(ResponseStatusException.class,()-> service.create(create3));
  }
  @Test void createRetryHasOneComplaintOneCreatedEventOneSubmissionAndRejectsChangedPayload() {
    var i=input(ComplaintTypeEnum.TENANT); var first=service.create(i); var retry=service.create(i);
    assertEquals(first.getId(),retry.getId()); assertEquals(1,db.rows(Complaint.class).size()); assertEquals(1,db.rows(Submission.class).size()); assertEquals(1,retry.getEvents().size());
    var create4 = new ComplaintDtos.Create(i.complaintType(),i.invoiceId(),"Changed",i.description(),i.requestId());
    assertThrows(ResponseStatusException.class,()-> service.create(create4));
  }
  @Test void sourcePersistsWhenSubmissionInsertFailsAndRecoveryIsIdempotent() {
    doThrow(new DataAccessResourceFailureException("offline")).doAnswer(a->db.insert(a.getArgument(0))).when(db.mongo).insert(any(Submission.class));
    var c=submit(); assertEquals(1,db.rows(Complaint.class).size()); assertTrue(db.rows(Submission.class).isEmpty());
    assertTrue(db.findByIdForTest(c.getId()).getHandoffs().get(0).pending());
    handoff.poll(); handoff.poll(); assertEquals(1,db.rows(Submission.class).size()); assertFalse(db.findByIdForTest(c.getId()).getHandoffs().get(0).pending());
  }
  @Test void customerAHasNoAccessToBComplaintEvenKnowingId() {
    var c=submit(); when(customers.current()).thenReturn(new CustomerAccess.Actor(OTHER,TENANT,null));
    var complaintId1 = c.getId();
    assertThrows(ResponseStatusException.class,()-> service.customerDetail(complaintId1)); assertTrue(service.customerList(null,0,20).content().isEmpty());
    var complaintId2 = c.getId();
    var reply1 = new ComplaintDtos.Reply(c.getRevision(),"reply");
    assertThrows(ResponseStatusException.class,()-> service.reply(complaintId2, reply1));
  }
  @Test void tenantAdminCannotReadOrUpdateAnotherTenantOrEcosystem() {
    var c=submit(); when(admins.current()).thenReturn(new ComplaintAccess.Actor(ADMIN,OTHER,List.of(ComplaintTypeEnum.TENANT),true,true));
    var complaintId3 = c.getId();
    assertThrows(ResponseStatusException.class,()-> service.adminDetail(complaintId3)); assertTrue(service.adminList(null,null,0,20).content().isEmpty());
    assertThrows(ResponseStatusException.class,()->action(c,"accept",null));
  }
  @Test void fullLifecycleRecordsAuditAndResolutionSubmissionWithoutChangingRoute() {
    var c=submit(); var id=c.getId(); c=action(c,"accept",null); assertEquals(ADMIN,c.getAssignedTo());
    c=action(c,"transition",ComplaintStatusEnum.WAITING_REPORTER);
    c=service.reply(id,new ComplaintDtos.Reply(c.getRevision(),"More information")); assertEquals(ComplaintStatusEnum.IN_PROGRESS,c.getStatus());
    c=action(c,"respond",null); c=action(c,"transition",ComplaintStatusEnum.RESOLVED); assertNotNull(c.getResolvedAt());
    c=action(c,"transition",ComplaintStatusEnum.CLOSED); assertNotNull(c.getClosedAt()); assertEquals(ComplaintStatusEnum.CLOSED,c.getStatus());
    assertEquals(7,c.getEvents().size()); assertEquals(ComplaintTypeEnum.TENANT,c.getComplaintType()); assertEquals(TENANT,c.getTenantId());
    assertEquals(2,db.rows(Submission.class).size());
    assertEquals(Set.of("COMPLAINT_CREATED","COMPLAINT_RESOLVED"),db.find(new org.springframework.data.mongodb.core.query.Query(),Submission.class).stream().map(Submission::event).collect(java.util.stream.Collectors.toSet()));
    var closed=c; assertThrows(ResponseStatusException.class,()->action(closed,"respond",null));
  }
  @Test void nullActionAndReplyRejectWithoutChangingComplaintOrSubmissions() {
    var complaint = submit();
    String id = complaint.getId();
    long revision = complaint.getRevision();
    int eventCount = complaint.getEvents().size();
    int submissionCount = db.rows(Submission.class).size();

    var actionError = assertThrows(ResponseStatusException.class, () -> service.action(id, "accept", null));
    assertEquals(HttpStatus.BAD_REQUEST, actionError.getStatusCode());
    var replyError = assertThrows(ResponseStatusException.class, () -> service.reply(id, null));
    assertEquals(HttpStatus.BAD_REQUEST, replyError.getStatusCode());

    var saved = db.findByIdForTest(id);
    assertEquals(revision, saved.getRevision());
    assertEquals(ComplaintStatusEnum.SUBMITTED, saved.getStatus());
    assertEquals(eventCount, saved.getEvents().size());
    assertEquals(submissionCount, db.rows(Submission.class).size());
  }
  @Test void invalidTransitionsCustomerRepliesAndEscalationAreRejected() {
    var c=submit();
    assertThrows(ResponseStatusException.class,()->action(c,"transition",ComplaintStatusEnum.CLOSED));
    var complaintId4 = c.getId();
    var reply2 = new ComplaintDtos.Reply(c.getRevision(),"Premature");
    assertThrows(ResponseStatusException.class,()-> service.reply(complaintId4, reply2));
    assertThrows(ResponseStatusException.class,()->action(c,"escalate",null));
    assertThrows(ResponseStatusException.class,()->action(c,"internal",null));
    var complaintId5 = c.getId();
    var action1 = new ComplaintDtos.Action(c.getRevision(),"spoof",ComplaintStatusEnum.CLOSED,OTHER);
    assertThrows(ResponseStatusException.class,()-> service.action(complaintId5, "accept", action1));
  }
  @Test void waitingReporterCanBeResumedByAdminAndStaleRevisionCannotRepeatAction() {
    var c=action(submit(),"accept",null); var old=c; c=action(c,"transition",ComplaintStatusEnum.WAITING_REPORTER);
    assertThrows(ResponseStatusException.class,()->action(old,"transition",ComplaintStatusEnum.RESOLVED));
    c=action(c,"transition",ComplaintStatusEnum.IN_PROGRESS); assertEquals(ComplaintStatusEnum.IN_PROGRESS,c.getStatus());
  }
  @Test void assignmentRequiresSeparatePermissionAndEligibleAdmin() {
    var c=submit();
    var complaintId6 = c.getId();
    var action2 = new ComplaintDtos.Action(c.getRevision(),"assign",null,OTHER);
    assertThrows(ResponseStatusException.class,()-> service.action(complaintId6, "assign", action2));
    when(admins.eligible(OTHER,TENANT,ComplaintTypeEnum.TENANT)).thenReturn(true);
    var assigned=service.action(c.getId(),"assign",new ComplaintDtos.Action(c.getRevision(),"assign",null,OTHER)); assertEquals(OTHER,assigned.getAssignedTo()); assertEquals("ASSIGNED",assigned.getEvents().get(1).eventType());
    assertTrue(assigned.getEvents().get(1).note().contains("UNASSIGNED -> "+OTHER));
    when(admins.current()).thenReturn(new ComplaintAccess.Actor(ADMIN,TENANT,List.of(ComplaintTypeEnum.TENANT),true,false));
    var complaintId7 = c.getId();
    var action3 = new ComplaintDtos.Action(assigned.getRevision(),"assign",null,ADMIN);
    assertThrows(ResponseStatusException.class,()-> service.action(complaintId7, "assign", action3));
  }
  @Test void concurrentAcceptanceCommitsExactlyOneTransitionAndAudit() throws Exception {
    var c=submit(); ExecutorService pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
    try {
      Callable<Boolean> work=()->{ start.await(); try { action(c,"accept",null); return true; } catch(ResponseStatusException conflict) { return false; } };
      var a=pool.submit(work); var b=pool.submit(work); start.countDown(); assertNotEquals(a.get(),b.get());
      assertEquals(2,db.findByIdForTest(c.getId()).getEvents().size()); assertEquals(2,db.findByIdForTest(c.getId()).getRevision());
    } finally { pool.shutdownNow(); }
  }
  @Test void missingEcoRouteFailsClosedWithoutFakeTenant() {
    when(admins.ecosystemTenant()).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
    var argument1 = input(ComplaintTypeEnum.ECOSYSTEM);
    assertThrows(ResponseStatusException.class,()-> service.create(argument1)); assertTrue(db.rows(Complaint.class).isEmpty());
  }
  @Test void realUc021OwnershipGuardRejectsUnownedOtherOwnerAndCrossTenantInvoices() {
    var realInvoices=new CoreCustomerInvoiceClient(new com.ecofood.app.customer.service.CustomerServiceImpl(customers,db.mongo));
    var guarded=new ComplaintServiceImpl(db.mongo,customers,realInvoices,admins,handoff,mock(NotificationDirectory.class));
    var i=new Invoice(); i.setId("invoice"); i.setTenantId(TENANT); db.insert(i);
    var request=input(ComplaintTypeEnum.TENANT);
    assertThrows(ResponseStatusException.class,()->guarded.create(request));
    var raw=db.raw(Invoice.class,"invoice"); raw.put("invoiceHolder",new Document("userId",OTHER).append("customerId",OTHER));
    assertThrows(ResponseStatusException.class,()->guarded.create(request));
    raw.put("invoiceHolder",new Document("userId",USER).append("customerId",USER)); raw.put("tenantId",OTHER);
    assertThrows(ResponseStatusException.class,()->guarded.create(request));
    raw.put("tenantId",TENANT); var before=new Document(raw); assertNotNull(guarded.create(request)); assertEquals(before,raw);
  }
  @Test void ecosystemAdminProcessesOnlyEcosystemQueueAndCannotChangeComplaintType() {
    var tenant=submit(); var eco=service.create(input(ComplaintTypeEnum.ECOSYSTEM));
    when(admins.current()).thenReturn(new ComplaintAccess.Actor(ADMIN,ECO,List.of(ComplaintTypeEnum.ECOSYSTEM),true,true));
    assertThrows(ResponseStatusException.class,()->action(tenant,"accept",null));
    eco=action(eco,"accept",null); eco=action(eco,"transition",ComplaintStatusEnum.RESOLVED); eco=action(eco,"transition",ComplaintStatusEnum.CLOSED);
    assertEquals(ComplaintTypeEnum.ECOSYSTEM,eco.getComplaintType()); assertNull(eco.getTenantId()); assertEquals(ECO,eco.getQueue().tenantId());
  }
}
