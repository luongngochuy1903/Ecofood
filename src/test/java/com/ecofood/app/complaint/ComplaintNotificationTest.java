package com.ecofood.app.complaint;

import static com.ecofood.app.complaint.ComplaintServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.customer.*;
import com.ecofood.app.notification.*;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

class ComplaintNotificationTest {
  AtomicComplaintMongo db; NotificationDirectory directory; ComplaintAccess access; ComplaintNotification adapter;
  NotificationProcessor processor; NotificationDelivery delivery; NotificationEmailSender sender; Complaint c; UserEntity admin;
  @BeforeEach void setup() throws Exception {
    db=new AtomicComplaintMongo(); directory=mock(NotificationDirectory.class); access=mock(ComplaintAccess.class);
    when(access.current()).thenReturn(new ComplaintAccess.Actor(ADMIN,TENANT,List.of(Complaint.Type.TENANT),true,true));
    when(access.eligible(ADMIN,TENANT,Complaint.Type.TENANT)).thenReturn(true);
    admin=new UserEntity(); admin.setId(new ObjectId(ADMIN)); admin.setEmail("admin@example.org");
    when(directory.user(ADMIN,TENANT)).thenReturn(admin); when(directory.users(TENANT)).thenReturn(List.of(admin));
    when(directory.claims(admin,TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"),List.of(ComplaintAccess.READ,ComplaintAccess.MANAGE),List.of()));
    var settings=new NotificationSettings("from@example.org",true,3,"");
    adapter=new ComplaintNotification(db.mongo,directory,access); var recipients=new NotificationRecipients(directory,settings); recipients.setAudiences(List.of(adapter));
    processor=new NotificationProcessor(db.mongo,List.of(adapter.complaintCreatedSource(),adapter.complaintResolvedSource()),recipients,new NotificationTemplate(),settings);
    sender=mock(NotificationEmailSender.class); delivery=new NotificationDelivery(db.mongo,sender,recipients,settings);
    c=new Complaint(); c.setId("complaint"); c.setCustomerId(USER); c.setCustomerTenantId(TENANT); c.setTenantId(TENANT); c.setComplaintType(Complaint.Type.TENANT);
    c.setQueue(new Complaint.Queue(TENANT,"TENANT_ADMIN")); c.setSubject("Subject"); c.setStatus(Complaint.Status.SUBMITTED);
    c.setCreatedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
  }
  NotificationRule rule(String event) {
    var r=new NotificationRule(); r.setId("rule-"+event); r.setTenantId(TENANT); r.setEventType(event); r.setEnabled(true); r.setRevision(1);
    r.setSubject("{eventType}: {complaintSubject}"); r.setContent("Complaint {complaintId} / {complaintStatus}");
    r.setRecipients(new NotificationRule.Recipients(List.of(),List.of("TENANT_ADMIN"),List.of(),false,List.of(),false)); return r;
  }
  Submission persist(String type) {
    var event=ComplaintService.submission(c,type,c.getCreatedAt()); c.setHandoffs(List.of(new Complaint.Handoff(event,false)));
    c.setEvents(List.of(new Complaint.ComplaintEvent("e",c.getId(),ADMIN,"RESOLVED",Complaint.Status.IN_PROGRESS,Complaint.Status.RESOLVED,c.getCreatedAt(),"Resolved safely")));
    db.insert(c); db.insert(event); return event;
  }
  @Test void createdUsesExistingRulesAndDeliveryPipelineThenRetryDoesNotDuplicateOrTouchKpi() {
    var event=persist("COMPLAINT_CREATED"); db.insert(rule(event.event())); db.raw(Submission.class,event.id()).put("actualMetrics",new Document("state","UNCHANGED"));
    processor.poll(); delivery.poll(); processor.poll(); delivery.poll();
    assertEquals(1,db.rows(Notification.class).size()); var row=db.find(new Query(),Notification.class).get(0);
    assertEquals("SENT",row.getStatus()); assertEquals("admin@example.org",row.getSendTo()); assertEquals(event.id(),row.getSubmissionId()); verify(sender,times(1)).send(any());
    assertEquals("UNCHANGED",((Document)db.raw(Submission.class,event.id()).get("actualMetrics")).getString("state"));
    assertFalse(db.collections.containsKey("invoice")); assertFalse(db.collections.containsKey("actual_metrics"));
  }
  @Test void missingOrDisabledRuleDoesNotSendEmailOrLoseComplaint() {
    var event=persist("COMPLAINT_CREATED"); var r=rule(event.event()); r.setEnabled(false); db.insert(r);
    processor.poll(); delivery.poll(); verifyNoInteractions(sender); assertEquals(1,db.rows(Complaint.class).size()); assertTrue(db.rows(Notification.class).isEmpty());
  }
  @Test void deliveryFailureDoesNotRollbackComplaintAndHasExistingRetryMetadata() {
    var event=persist("COMPLAINT_CREATED"); db.insert(rule(event.event()));
    doThrow(new NotificationEmailSender.DeliveryFailure("SMTP_CONNECT_FAILED",true,false)).when(sender).send(any());
    processor.poll(); delivery.poll(); assertEquals(1,db.rows(Complaint.class).size());
    var n=db.find(new Query(),Notification.class).get(0); assertEquals("RETRY_PENDING",n.getStatus()); assertEquals("SMTP_CONNECT_FAILED",n.getLastError());
  }
  @Test void resolvedTargetsOnlyVerifiedReporterEvenForEcosystemWithDifferentTenant() {
    var reporter=new CustomerUser(); reporter.setId(new ObjectId(USER)); reporter.setEmail("customer@example.org"); reporter.setExternalLoginId("kc-user"); reporter.setCustomerEmailVerifiedAt(Instant.now());
    reporter.setCustomerVerifiedEmailHash(ComplaintService.hash(reporter.getEmail())); reporter.setUserCategory(UserCategory.builder().userCategoryCode("CUSTOMER").build());
    reporter.setPartyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(TENANT)).relationshipTypeCode("TENANT").build())); db.insert(reporter);
    c.setComplaintType(Complaint.Type.ECOSYSTEM); c.setTenantId(null); c.setQueue(new Complaint.Queue(ECO,"ECO_ADMIN")); c.setStatus(Complaint.Status.RESOLVED);
    var event=persist("COMPLAINT_RESOLVED"); var r=rule(event.event()); r.setTenantId(ECO);
    r.setRecipients(new NotificationRule.Recipients(List.of(),List.of(),List.of(),false,List.of(),true)); db.insert(r);
    processor.poll(); delivery.poll(); var n=db.find(new Query(),Notification.class).get(0);
    assertEquals(ECO,n.getTenantId()); assertEquals(USER,n.getRecipientUserId()); assertEquals("customer@example.org",n.getSendTo()); assertEquals("SENT",n.getStatus());
    db.raw(CustomerUser.class,new ObjectId(USER)).put("email","changed@example.org"); assertFalse(adapter.stillAllowed(n));
  }
  @Test void wrongTenantRecipientAndForgedEventCannotLeakComplaint() {
    var event=persist("COMPLAINT_CREATED"); var r=rule(event.event());
    when(access.eligible(ADMIN,TENANT,Complaint.Type.TENANT)).thenReturn(false); assertTrue(adapter.resolve(r,event).isEmpty());
    var forged=new Submission("forged",event.event(),event.createdAt(),ECO,"COMPLAINT",c.getId(),null,null,null,0);
    assertThrows(IllegalStateException.class,()->adapter.resolve(r,forged));
  }
  @Test void complaintRuleValidationBlocksExternalAndUnrelatedCustomerEmails() {
    var resolved=new NotificationRule.Recipients(List.of(),List.of(),List.of(),false,List.of(),true);
    assertDoesNotThrow(()->adapter.validate("COMPLAINT_RESOLVED",resolved,TENANT));
    assertThrows(ResponseStatusException.class,()->adapter.validate("COMPLAINT_CREATED",resolved,TENANT));
    assertThrows(ResponseStatusException.class,()->adapter.validate("COMPLAINT_RESOLVED",new NotificationRule.Recipients(List.of(OTHER),List.of(),List.of(),false,List.of(),true),TENANT));
    assertThrows(ResponseStatusException.class,()->adapter.validate("COMPLAINT_CREATED",new NotificationRule.Recipients(List.of(),List.of("TENANT_ADMIN"),List.of(),false,List.of("external@example.org")),TENANT));
  }
}
