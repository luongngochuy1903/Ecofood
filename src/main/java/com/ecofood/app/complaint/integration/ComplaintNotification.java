package com.ecofood.app.complaint.integration;

import com.ecofood.app.complaint.entity.Complaint;
import com.ecofood.app.complaint.security.ComplaintAccess;
import com.ecofood.app.complaint.service.ComplaintServiceImpl;
import com.ecofood.app.customer.entity.CustomerUser;
import com.ecofood.app.notification.config.NotificationSettings;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRule;
import com.ecofood.app.notification.integration.NotificationAudience;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.notification.integration.NotificationEventSource;
import com.ecofood.app.notification.service.NotificationRecipients;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.core.dto.NotificationDtos;
import com.ecofood.core.enums.ComplaintTypeEnum;
import java.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Complaint-specific audience, reused by generation and by SMTP delivery revalidation. */
@Configuration
@RequiredArgsConstructor
public class ComplaintNotification implements NotificationAudience {
  private final MongoTemplate mongo;
  private final NotificationDirectory directory;
  private final ComplaintAccess access;
  @Bean public NotificationEventSource complaintCreatedSource() { return source("COMPLAINT_CREATED"); }
  @Bean public NotificationEventSource complaintResolvedSource() { return source("COMPLAINT_RESOLVED"); }
  @Override public boolean supports(String event) { return "COMPLAINT_CREATED".equals(event) || "COMPLAINT_RESOLVED".equals(event); }
  private NotificationEventSource source(String type) {
    return new NotificationEventSource() {
      public String event() { return type; }
      public Context resolve(Submission event) {
        var c=require(event); Map<String,String> values=new HashMap<>();
        values.put("submissionId",event.id()); values.put("eventType",event.event()); values.put("tenantId",event.tenantId());
        values.put("complaintId",c.getId()); values.put("complaintSubject",c.getSubject()); values.put("complaintType",c.getComplaintType().name());
        values.put("complaintStatus",type.equals("COMPLAINT_CREATED")?"SUBMITTED":"RESOLVED");
        if(type.equals("COMPLAINT_RESOLVED")) c.getEvents().stream().filter(e->"RESOLVED".equals(e.eventType())).findFirst().ifPresent(e->values.put("resolution",e.note()));
        return new Context(null,true,Map.copyOf(values));
      }
    };
  }
  private Complaint require(Submission event) {
    if(event==null || !supports(event.event()) || !"COMPLAINT".equals(event.sourceType())) throw new IllegalStateException("INVALID_EVENT_SOURCE");
    var c=mongo.findById(event.sourceId(),Complaint.class);
    if(c==null || c.getQueue()==null || !Objects.equals(c.getQueue().tenantId(),event.tenantId()) || c.getHandoffs()==null
        || c.getHandoffs().stream().noneMatch(h->h.submission().equals(event))) throw new IllegalStateException("INVALID_EVENT_SOURCE");
    return c;
  }
  @Override public void validate(String event,NotificationDtos.Recipients r,String tenant) {
    var actor=access.current(); actor.requireManage(); if(!actor.tenantId().equals(tenant)) throw ComplaintAccess.denied();
    if(r.invoiceCustomer() || !safe(r.emails()).isEmpty()) throw bad("Complaint recipients must be scoped users/roles; no external emails or Invoice customer selector");
    if("COMPLAINT_RESOLVED".equals(event)) {
      if(!Boolean.TRUE.equals(r.complaintCustomer()) || !safe(r.userIds()).isEmpty() || !safe(r.roles()).isEmpty() || !safe(r.permissions()).isEmpty())
        throw bad("COMPLAINT_RESOLVED requires only complaintCustomer=true");
    } else {
      if(Boolean.TRUE.equals(r.complaintCustomer())) throw bad("COMPLAINT_CREATED is for queue Admins only");
      for(String id:safe(r.userIds())) if(actor.types().stream().noneMatch(t->access.eligible(id,tenant,t))) throw bad("Recipient is not an eligible queue Admin");
      for(String role:safe(r.roles())) if(!(role.equals("TENANT_ADMIN") && actor.types().contains(ComplaintTypeEnum.TENANT))
          && !(role.equals("ECO_ADMIN") && actor.types().contains(ComplaintTypeEnum.ECOSYSTEM))) throw bad("Role is not an eligible queue Admin");
    }
  }
  @Override public List<NotificationRecipients.Recipient> resolve(NotificationRule rule,Submission event) {
    var c=require(event); if(!Objects.equals(rule.getTenantId(),event.tenantId()) || !Objects.equals(rule.getEventType(),event.event())) return List.of();
    var config=rule.getRecipients(); if(config==null) return List.of();
    if("COMPLAINT_RESOLVED".equals(event.event())) {
      if(!Boolean.TRUE.equals(config.complaintCustomer())) return List.of();
      var u=verifiedReporter(c); return u==null?List.of():List.of(new NotificationRecipients.Recipient(canonical(u.getEmail()),u.getIdAsString()));
    }
    Map<String,NotificationRecipients.Recipient> result=new TreeMap<>();
    for(var user:directory.users(event.tenantId())) {
      if(!access.eligible(user.getIdAsString(),event.tenantId(),c.getComplaintType()) || !NotificationSettings.validEmail(user.getEmail())) continue;
      var claims=directory.claims(user,event.tenantId());
      if(safe(config.userIds()).contains(user.getIdAsString()) || !Collections.disjoint(claims.roles(),safe(config.roles())) || !Collections.disjoint(claims.permissions(),safe(config.permissions()))) {
        String email=canonical(user.getEmail()); result.put(email,new NotificationRecipients.Recipient(email,user.getIdAsString()));
      }
    }
    return List.copyOf(result.values());
  }
  @Override public boolean stillAllowed(Notification notification) {
    try {
      var event=mongo.findById(notification.getSubmissionId(),Submission.class);
    var c=require(event);
      if(!Objects.equals(event.tenantId(),notification.getTenantId()) || !Objects.equals(event.event(),notification.getType())) return false;
      if("COMPLAINT_RESOLVED".equals(event.event())) {
        var reporter=verifiedReporter(c); return reporter!=null && reporter.getIdAsString().equals(notification.getRecipientUserId()) && canonical(reporter.getEmail()).equals(notification.getSendTo());
      }
      if(notification.getRecipientUserId()==null || !access.eligible(notification.getRecipientUserId(),event.tenantId(),c.getComplaintType())) return false;
      var user=directory.user(notification.getRecipientUserId(),event.tenantId());
      return user!=null && NotificationSettings.validEmail(user.getEmail()) && canonical(user.getEmail()).equals(notification.getSendTo());
    } catch(RuntimeException invalid) { return false; }
  }
  private CustomerUser verifiedReporter(Complaint c) {
    var u=mongo.findById(new ObjectId(c.getCustomerId()),CustomerUser.class);
    if(!NotificationDirectory.belongs(u,c.getCustomerTenantId()) || u.getUserCategory()==null || !"CUSTOMER".equals(u.getUserCategory().getUserCategoryCode())
        || u.getExternalLoginId()==null || u.getCustomerEmailVerifiedAt()==null || !NotificationSettings.validEmail(u.getEmail())) return null;
    String digest=ComplaintServiceImpl.hash(canonical(u.getEmail()));
    return digest.equals(u.getCustomerVerifiedEmailHash())?u:null;
  }
  private static List<String> safe(List<String> values) { return values==null?List.of():values; }
  private static String canonical(String email) { return email.trim().toLowerCase(Locale.ROOT); }
  private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
