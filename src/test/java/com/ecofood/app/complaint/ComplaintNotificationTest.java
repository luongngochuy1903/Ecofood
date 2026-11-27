package com.ecofood.app.complaint;

import static com.ecofood.app.complaint.ComplaintServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.complaint.entity.Complaint;
import com.ecofood.app.complaint.integration.ComplaintNotification;
import com.ecofood.app.complaint.security.ComplaintAccess;
import com.ecofood.app.complaint.service.ComplaintServiceImpl;
import com.ecofood.app.customer.entity.CustomerUser;
import com.ecofood.app.notification.config.NotificationSettings;
import com.ecofood.app.notification.delivery.NotificationDelivery;
import com.ecofood.app.notification.delivery.NotificationEmailSender;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRule;
import com.ecofood.app.notification.exception.DeliveryFailure;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.notification.service.NotificationProcessor;
import com.ecofood.app.notification.service.NotificationRecipients;
import com.ecofood.app.notification.service.NotificationTemplate;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.dto.ComplaintDtos;
import com.ecofood.core.dto.NotificationDtos;
import com.ecofood.core.enums.ComplaintStatusEnum;
import com.ecofood.core.enums.ComplaintTypeEnum;
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
    when(access.current()).thenReturn(new ComplaintAccess.Actor(ADMIN,TENANT,List.of(ComplaintTypeEnum.TENANT),true,true));
    when(access.eligible(ADMIN,TENANT,ComplaintTypeEnum.TENANT)).thenReturn(true);
    admin=new UserEntity(); admin.setId(new ObjectId(ADMIN)); admin.setEmail("admin@example.org");
    when(directory.user(ADMIN,TENANT)).thenReturn(admin); when(directory.users(TENANT)).thenReturn(List.of(admin));
    when(directory.claims(admin,TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"),List.of(ComplaintAccess.READ,ComplaintAccess.MANAGE),List.of()));
    var settings=new NotificationSettings("from@example.org",true,3,"");
    adapter=new ComplaintNotification(db.mongo,directory,access); var recipients=new NotificationRecipients(directory,settings); recipients.setAudiences(List.of(adapter));
    processor=new NotificationProcessor(db.mongo,List.of(adapter.complaintCreatedSource(),adapter.complaintResolvedSource()),recipients,new NotificationTemplate(),settings,new com.ecofood.app.notification.service.ComplaintEmailRenderer(new com.ecofood.app.notification.service.ComplaintEmailBranding(db.mongo,"https"),directory));
    sender=mock(NotificationEmailSender.class); delivery=new NotificationDelivery(db.mongo,sender,recipients,settings);
    c=new Complaint(); c.setId("complaint"); c.setCustomerId(USER); c.setCustomerTenantId(TENANT); c.setTenantId(TENANT); c.setComplaintType(ComplaintTypeEnum.TENANT);
    c.setQueue(new ComplaintDtos.Queue(TENANT,"TENANT_ADMIN")); c.setSubject("Subject"); c.setDescription("Service was unavailable"); c.setInvoiceId("invoice-123"); c.setStatus(ComplaintStatusEnum.SUBMITTED);
    c.setCreatedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
  }
  NotificationRule rule(String event) {
    var r=new NotificationRule(); r.setId("rule-"+event); r.setTenantId(TENANT); r.setEventType(event); r.setEnabled(true); r.setRevision(1);
    r.setSubject("{eventType}: {complaintSubject}"); r.setContent("Complaint {complaintId} / {complaintStatus}");
    r.setRecipients(new NotificationDtos.Recipients(List.of(),List.of("TENANT_ADMIN"),List.of(),false,List.of(),false)); return r;
  }
  Submission persist(String type) {
    var event=ComplaintServiceImpl.submission(c,type,c.getCreatedAt()); c.setHandoffs(List.of(new Complaint.Handoff(event,false)));
    c.setEvents(List.of(new ComplaintDtos.ComplaintEvent("e",c.getId(),ADMIN,"RESOLVED",ComplaintStatusEnum.IN_PROGRESS,ComplaintStatusEnum.RESOLVED,c.getCreatedAt(),"Resolved safely")));
    db.insert(c); db.insert(event); return event;
  }
  @Test void createdIgnoresRulesAndDeliveryPipelineRetryDoesNotDuplicateOrTouchKpi() {
    var event=persist("COMPLAINT_CREATED"); db.insert(rule(event.event())); db.raw(Submission.class,event.id()).put("actualMetrics",new Document("state","UNCHANGED"));
    processor.poll(); delivery.poll(); processor.poll(); delivery.poll();
    assertEquals(1,db.rows(Notification.class).size()); var row=db.find(new Query(),Notification.class).get(0);
    assertEquals("SENT",row.getStatus()); assertEquals("admin@example.org",row.getSendTo()); assertEquals(event.id(),row.getSubmissionId()); verify(sender,times(1)).send(any());
    assertEquals("UNCHANGED",((Document)db.raw(Submission.class,event.id()).get("actualMetrics")).getString("state"));
    assertFalse(db.collections.containsKey("invoice")); assertFalse(db.collections.containsKey("actual_metrics"));
    verify(db.mongo,never()).find(any(Query.class),eq(NotificationRule.class));
  }
  @Test void disabledRuleCannotPreventComplaintEmail() {
    var event=persist("COMPLAINT_CREATED"); var r=rule(event.event()); r.setEnabled(false); db.insert(r);
    processor.poll(); delivery.poll(); verify(sender).send(any()); assertEquals(1,db.rows(Complaint.class).size());
    assertEquals("SENT",db.find(new Query(),Notification.class).get(0).getStatus());
  }
  @Test void serviceComplaintDefaultsToQueueAdminsWithOnlyComplaintAndInvoiceReferencesAndContent() {
    persist("COMPLAINT_CREATED"); processor.poll(); delivery.poll();
    var notice=db.find(new Query(),Notification.class).get(0);
    assertEquals("SYSTEM_COMPLAINT_CREATED",notice.getRuleId());
    assertTrue(notice.getContent().contains("Service was unavailable")); assertTrue(notice.getContent().contains("invoice-123"));
    assertTrue(com.ecofood.app.notification.service.EmailHtml.supported(notice.getType(),notice.getContent()));
    assertEquals("[EcoFood] New Complaint Submitted – complaint",notice.getSubject());
    assertEquals("SENT",notice.getStatus());
  }
  @Test void platformComplaintDoesNotIncludeInvoiceEvenIfLegacyComplaintHasOneOrRuleRequestsIt() {
    c.setComplaintType(ComplaintTypeEnum.ECOSYSTEM); c.setQueue(new ComplaintDtos.Queue(ECO,"ECO_ADMIN"));
    when(directory.users(ECO)).thenReturn(List.of(admin)); when(directory.user(ADMIN,ECO)).thenReturn(admin);
    when(access.eligible(ADMIN,ECO,ComplaintTypeEnum.ECOSYSTEM)).thenReturn(true);
    when(directory.claims(admin,ECO)).thenReturn(new UserAccessClaims(List.of("ECO_ADMIN"),List.of(),List.of()));
    var event=persist("COMPLAINT_CREATED"); var rule=rule(event.event()); rule.setTenantId(ECO);
    rule.setRecipients(new NotificationDtos.Recipients(List.of(ADMIN),List.of(),List.of(),false,List.of(),false));
    rule.setContent("Legacy template {invoiceNumber}"); db.insert(rule);
    processor.poll(); delivery.poll();
    var notice=db.find(new Query(),Notification.class).get(0);
    assertTrue(notice.getContent().contains("Service was unavailable")); assertFalse(notice.getContent().contains("Invoice ID"));
    assertFalse(notice.getContent().contains("invoice-123")); assertEquals("SENT",notice.getStatus());
  }
  @Test void deliveryFailureDoesNotRollbackComplaintAndHasExistingRetryMetadata() {
    var event=persist("COMPLAINT_CREATED"); db.insert(rule(event.event()));
    doThrow(new DeliveryFailure("SMTP_CONNECT_FAILED",true,false)).when(sender).send(any());
    processor.poll(); delivery.poll(); assertEquals(1,db.rows(Complaint.class).size());
    var n=db.find(new Query(),Notification.class).get(0); assertEquals("RETRY_PENDING",n.getStatus()); assertEquals("SMTP_CONNECT_FAILED",n.getLastError());
  }
  @Test void htmlContentRemainsFrozenAcrossGenerationRetryAndLaterComplaintEdits() {
    var event=persist("COMPLAINT_CREATED");
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).doAnswer(a -> db.insert(a.getArgument(0))).when(db.mongo).insert(any(Notification.class));
    processor.poll(); assertTrue(db.rows(Notification.class).isEmpty());
    var state=(Document)db.raw(Submission.class,event.id()).get("notification");
    assertNotNull(state.get("plan"));
    db.raw(Complaint.class,c.getId()).put("description","Later complaint edit");
    state.put("nextAttemptAt",Date.from(Instant.now().minusSeconds(1)));
    processor.poll(); delivery.poll(); processor.poll(); delivery.poll();
    var notice=db.find(new Query(),Notification.class).get(0);
    assertTrue(notice.getContent().contains("Service was unavailable")); assertFalse(notice.getContent().contains("Later complaint edit"));
    assertEquals(1,db.rows(Notification.class).size()); verify(sender,times(1)).send(any());
  }
  @Test void resolvedTargetsOnlyVerifiedReporterEvenForEcosystemWithDifferentTenant() {
    var reporter=new CustomerUser(); reporter.setId(new ObjectId(USER)); reporter.setEmail("customer@example.org"); reporter.setExternalLoginId("kc-user"); reporter.setCustomerEmailVerifiedAt(Instant.now());
    reporter.setCustomerVerifiedEmailHash(ComplaintServiceImpl.hash(reporter.getEmail())); reporter.setUserCategory(UserCategory.builder().userCategoryCode("CUSTOMER").build());
    reporter.setPartyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(TENANT)).relationshipTypeCode("TENANT").build())); db.insert(reporter);
    c.setComplaintType(ComplaintTypeEnum.ECOSYSTEM); c.setTenantId(null); c.setQueue(new ComplaintDtos.Queue(ECO,"ECO_ADMIN")); c.setStatus(ComplaintStatusEnum.RESOLVED);
    persist("COMPLAINT_RESOLVED");
    processor.poll(); delivery.poll(); var n=db.find(new Query(),Notification.class).get(0);
    assertEquals(ECO,n.getTenantId()); assertEquals(USER,n.getRecipientUserId()); assertEquals("customer@example.org",n.getSendTo()); assertEquals("SENT",n.getStatus());
    assertTrue(n.getContent().contains("Resolved safely")); assertFalse(n.getContent().contains("Submission ID"));
    verify(db.mongo,never()).find(any(Query.class),eq(NotificationRule.class));
    db.raw(CustomerUser.class,new ObjectId(USER)).put("email","changed@example.org"); assertFalse(adapter.stillAllowed(n));
  }
  @Test void wrongTenantRecipientAndForgedEventCannotLeakComplaint() {
    var event=persist("COMPLAINT_CREATED"); var r=rule(event.event());
    when(access.eligible(ADMIN,TENANT,ComplaintTypeEnum.TENANT)).thenReturn(false); assertTrue(adapter.resolve(r,event).isEmpty());
    var forged=new Submission("forged",event.event(),event.createdAt(),ECO,"COMPLAINT",c.getId(),null,null,null,0);
    assertThrows(IllegalStateException.class,()->adapter.resolve(r,forged));
  }
  @Test void complaintRuleValidationBlocksExternalAndUnrelatedCustomerEmails() {
    var resolved=new NotificationDtos.Recipients(List.of(),List.of(),List.of(),false,List.of(),true);
    assertDoesNotThrow(()->adapter.validate("COMPLAINT_RESOLVED",resolved,TENANT));
    assertThrows(ResponseStatusException.class,()->adapter.validate("COMPLAINT_CREATED",resolved,TENANT));
    var recipients1 = new NotificationDtos.Recipients(List.of(OTHER),List.of(),List.of(),false,List.of(),true);
    assertThrows(ResponseStatusException.class,()-> adapter.validate("COMPLAINT_RESOLVED", recipients1, TENANT));
    var recipients2 = new NotificationDtos.Recipients(List.of(),List.of("TENANT_ADMIN"),List.of(),false,List.of("external@example.org"));
    assertThrows(ResponseStatusException.class,()-> adapter.validate("COMPLAINT_CREATED", recipients2, TENANT));
  }
  @Test void everyEligibleQueueAdminReceivesEmailEvenWhenOldRuleSelectsOnlyOne() {
    var second=new UserEntity(); second.setId(new ObjectId(OTHER)); second.setEmail("second@example.org");
    when(directory.users(TENANT)).thenReturn(List.of(admin,second)); when(directory.user(OTHER,TENANT)).thenReturn(second);
    when(access.eligible(OTHER,TENANT,ComplaintTypeEnum.TENANT)).thenReturn(true);
    var event=persist("COMPLAINT_CREATED"); var r=rule(event.event());
    r.setRecipients(new NotificationDtos.Recipients(List.of(ADMIN),List.of(),List.of(),false,List.of(),false)); db.insert(r);
    processor.poll(); delivery.poll();
    assertEquals(Set.of("admin@example.org","second@example.org"),db.find(new Query(),Notification.class).stream().map(Notification::getSendTo).collect(java.util.stream.Collectors.toSet()));
    verify(sender,times(2)).send(any());
  }
  @Test void legacyDisabledDeliveryFlagCannotStopSending() {
    var settings=new NotificationSettings("from@example.org",false,3,"");
    assertTrue(settings.deliveryEnabled);
    persist("COMPLAINT_CREATED"); processor.poll();
    var recipients=new NotificationRecipients(directory,settings); recipients.setAudiences(List.of(adapter));
    new NotificationDelivery(db.mongo,sender,recipients,settings).poll();
    assertEquals("SENT",db.find(new Query(),Notification.class).get(0).getStatus()); verify(sender).send(any());
  }
}
