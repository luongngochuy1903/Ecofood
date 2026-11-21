package com.ecofood.app.complaint;

import static com.ecofood.app.complaint.ComplaintDtos.*;
import com.ecofood.app.customer.*;
import com.ecofood.app.notification.NotificationDirectory;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ComplaintService {
  private final MongoTemplate mongo;
  private final CustomerAccess customers;
  private final CustomerService invoices;
  private final ComplaintAccess admins;
  private final ComplaintHandoff handoff;
  private final NotificationDirectory directory;
  public Complaint create(Create input) {
    var actor=customers.current(); validate(input);
    String id=hash("complaint\n"+actor.userId()+"\n"+actor.tenantId()+"\n"+input.requestId());
    var existing=mongo.findById(id,Complaint.class);
    if(existing!=null) return retry(actor,input,existing);
    Invoice invoice=null;
    // Even optional ECOSYSTEM invoice references must be owned; no arbitrary Invoice disclosure.
    if(blank(input.invoiceId())!=null) invoice=invoices.requireOwned(actor,input.invoiceId());
    if(input.complaintType()==Complaint.Type.TENANT && invoice==null) throw bad("TENANT Complaint requires an owned Invoice");
    String target=input.complaintType()==Complaint.Type.TENANT?invoice.getTenantId():admins.ecosystemTenant();
    var c=new Complaint(); c.setId(id); c.setComplaintType(input.complaintType()); c.setCustomerId(actor.userId()); c.setCustomerTenantId(actor.tenantId());
    c.setInvoiceId(invoice==null?null:invoice.getId()); c.setTenantId(input.complaintType()==Complaint.Type.TENANT?target:null);
    c.setQueue(new Complaint.Queue(target,input.complaintType()==Complaint.Type.TENANT?"TENANT_ADMIN":"ECO_ADMIN"));
    c.setSubject(input.subject().trim()); c.setDescription(input.description().trim()); c.setStatus(Complaint.Status.SUBMITTED);
    c.setCreatedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); c.setUpdatedAt(c.getCreatedAt()); c.setRevision(1);
    c.setEvents(List.of(event(c,actor.userId(),"CREATED",null,c.getStatus(),"Complaint submitted",c.getCreatedAt())));
    c.setHandoffs(List.of(new Complaint.Handoff(submission(c,"COMPLAINT_CREATED",c.getCreatedAt()),true)));
    try { mongo.insert(c); } catch(DuplicateKeyException race) {
      var winner=mongo.findById(id,Complaint.class);
      if(winner==null) {
        throw conflict();
      }
      return retry(actor,input,winner);
    }
    handoff.deliver(c); return c;
  }
  private Complaint retry(CustomerAccess.Actor actor,Create input,Complaint existing) {
    if(!actor.userId().equals(existing.getCustomerId()) || !actor.tenantId().equals(existing.getCustomerTenantId())
        || existing.getComplaintType()!=input.complaintType() || !Objects.equals(existing.getInvoiceId(),blank(input.invoiceId()))
        || !existing.getSubject().equals(input.subject().trim()) || !existing.getDescription().equals(input.description().trim())) throw conflict();
    handoff.deliver(existing); return existing;
  }
  public Page<Complaint> customerList(Complaint.Status status,int page,int size) {
    var actor=customers.current(); return list(Criteria.where("customerId").is(actor.userId()).and("customerTenantId").is(actor.tenantId()),status,page,size);
  }
  public Complaint customerDetail(String id) { return customerFind(customers.current(),id); }
  private Complaint customerFind(CustomerAccess.Actor actor,String id) {
    var c=mongo.findOne(Query.query(Criteria.where("_id").is(id).and("customerId").is(actor.userId()).and("customerTenantId").is(actor.tenantId())),Complaint.class);
    if(c==null) throw ComplaintAccess.missing(); return c;
  }
  public Complaint reply(String id,Reply input) {
    var actor=customers.current(); var c=customerFind(actor,id); check(input==null?null:input.revision(),input==null?null:input.note(),c);
    if(c.getStatus()!=Complaint.Status.WAITING_REPORTER) throw conflict();
    return write(c,actor.userId(),"REPORTER_REPLIED",Complaint.Status.IN_PROGRESS,input.note(),new Update(),null);
  }
  public Capabilities capabilities() { var a=admins.current(); return new Capabilities(a.tenantId(),a.types(),a.manage(),a.assign()); }
  public List<Assignee> assignees(Complaint.Type type) { return admins.assignees(admins.current(),type); }
  public Page<Complaint> adminList(Complaint.Type type,Complaint.Status status,int page,int size) {
    var a=admins.current(); if(type!=null && !a.types().contains(type)) throw ComplaintAccess.denied();
    return list(Criteria.where("queue.tenantId").is(a.tenantId()).and("complaintType").in(type==null?a.types():List.of(type)),status,page,size);
  }
  public Detail adminDetail(String id) {
    var a=admins.current(); var c=adminFind(a,id);
    var u=directory.user(c.getCustomerId(),c.getCustomerTenantId());
    String name=u==null?null:Objects.toString(u.getFirstName(),"")+" "+Objects.toString(u.getLastName(),"");
    // Minimal reference only, never expose financial snapshot/POS payload to complaint handlers.
    var i=c.getInvoiceId()==null || c.getComplaintType()!=Complaint.Type.TENANT?null:mongo.findOne(Query.query(Criteria.where("_id").is(c.getInvoiceId()).and("tenantId").is(a.tenantId())
        .and("invoiceHolder.userId").is(c.getCustomerId())),Invoice.class);
    return new Detail(c,name,i==null?null:i.getInvoiceNumber());
  }
  private Complaint adminFind(ComplaintAccess.Actor a,String id) {
    var c=mongo.findOne(Query.query(Criteria.where("_id").is(id).and("queue.tenantId").is(a.tenantId()).and("complaintType").in(a.types())),Complaint.class);
    if(c==null) throw ComplaintAccess.missing(); admins.requireScope(a,c); return c;
  }
  public Complaint action(String id,String action,Action input) {
    var a=admins.current(); a.requireManage(); var c=adminFind(a,id);
    check(input==null?null:input.revision(),input==null?null:input.note(),c);
    Complaint.Status next=c.getStatus(); Update update=new Update(); String event; String note=input.note();
    if(!"assign".equals(action) && blank(input.assignedTo())!=null) throw bad("Assignee only accepted by assign action");
    if(!"transition".equals(action) && input.status()!=null) throw bad("Status only accepted by transition action");
    switch(action) {
      case "accept" -> {
        if(c.getStatus()!=Complaint.Status.SUBMITTED) throw conflict();
        next=Complaint.Status.IN_PROGRESS; update.set("assignedTo",a.userId()); event="ACCEPTED";
        note="Assignee: "+Objects.toString(c.getAssignedTo(),"UNASSIGNED")+" -> "+a.userId()+"\n"+note;
      }
      case "assign" -> {
        if(!a.assign()) throw ComplaintAccess.denied();
        if(c.getStatus()==Complaint.Status.RESOLVED || c.getStatus()==Complaint.Status.CLOSED) throw conflict();
        if(blank(input.assignedTo())==null || !admins.eligible(input.assignedTo(),a.tenantId(),c.getComplaintType())) throw bad("Assignee must be an authorized Admin in this queue");
        update.set("assignedTo",input.assignedTo()); event="ASSIGNED";
        note="Assignee: "+Objects.toString(c.getAssignedTo(),"UNASSIGNED")+" -> "+input.assignedTo()+"\n"+note;
      }
      case "respond" -> {
        if(c.getStatus()!=Complaint.Status.IN_PROGRESS && c.getStatus()!=Complaint.Status.WAITING_REPORTER) throw conflict();
        event="ADMIN_REPLIED";
      }
      case "transition" -> {
        next=input.status(); if(!allowed(c.getStatus(),next)) throw conflict();
        event=next==Complaint.Status.RESOLVED?"RESOLVED":next==Complaint.Status.CLOSED?"CLOSED":"STATUS_CHANGED";
      }
      default -> throw bad("Unsupported Complaint action");
    }
    return write(c,a.userId(),event,next,note,update,next==Complaint.Status.RESOLVED?"COMPLAINT_RESOLVED":null);
  }
  static boolean allowed(Complaint.Status from,Complaint.Status to) {
    if(to==null) return false;
    return switch(from) {
      case IN_PROGRESS -> to==Complaint.Status.WAITING_REPORTER || to==Complaint.Status.RESOLVED;
      case WAITING_REPORTER -> to==Complaint.Status.IN_PROGRESS;
      case RESOLVED -> to==Complaint.Status.CLOSED;
      default -> false;
    };
  }
  private Complaint write(Complaint c,String actor,String type,Complaint.Status next,String note,Update update,String businessEvent) {
    Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    update.set("status",next).set("updatedAt",now).inc("revision",1).push("events",event(c,actor,type,c.getStatus(),next,note.trim(),now));
    if(next==Complaint.Status.RESOLVED && c.getStatus()!=next) update.set("resolvedAt",now);
    if(next==Complaint.Status.CLOSED) update.set("closedAt",now);
    if(businessEvent!=null) update.push("handoffs",new Complaint.Handoff(submission(c,businessEvent,now),true));
    var result=mongo.findAndModify(Query.query(Criteria.where("_id").is(c.getId()).and("revision").is(c.getRevision()).and("status").is(c.getStatus())
        .and("customerId").is(c.getCustomerId()).and("queue.tenantId").is(c.getQueue().tenantId())),update,FindAndModifyOptions.options().returnNew(true),Complaint.class);
    if(result==null) throw conflict(); handoff.deliver(result); return result;
  }
  private Page<Complaint> list(Criteria scope,Complaint.Status status,int page,int size) {
    if(page<0 || page>100000 || size<1 || size>100) throw bad("Invalid pagination");
    Query q=Query.query(scope); if(status!=null) q.addCriteria(Criteria.where("status").is(status));
    var rows=mongo.find(q.with(Sort.by(Sort.Direction.DESC,"updatedAt","id")).skip((long)page*size).limit(size+1),Complaint.class);
    return new Page<>(rows.stream().limit(size).toList(),page,size,rows.size()>size);
  }
  private void validate(Create input) {
    if(input==null || input.complaintType()==null || input.subject()==null || input.subject().isBlank() || input.subject().length()>200
        || input.description()==null || input.description().isBlank() || input.description().length()>10000) throw bad("Invalid Complaint content");
    try { if(!UUID.fromString(input.requestId()).toString().equals(input.requestId())) throw new IllegalArgumentException(); }
    catch(RuntimeException invalid) { throw bad("requestId must be a full UUID"); }
    if(input.complaintType()==Complaint.Type.TENANT && blank(input.invoiceId())==null) throw bad("TENANT Complaint requires invoiceId");
    if(input.invoiceId()!=null && input.invoiceId().length()>100) throw bad("Invalid Invoice reference");
  }
  private void check(Long revision,String note,Complaint c) {
    if(revision==null || note==null || note.isBlank() || note.length()>4000) throw bad("Current revision and note are required");
    if(revision!=c.getRevision()) throw conflict();
    if(c.getEvents()!=null && c.getEvents().size()>=1000) throw new ResponseStatusException(HttpStatus.CONFLICT,"Complaint audit limit reached; contact support");
  }
  static Submission submission(Complaint c,String event,Instant now) {
    return new Submission(hash(c.getId()+"\n"+event),event,now,c.getQueue().tenantId(),"COMPLAINT",c.getId(),null,null,null,0);
  }
  private Complaint.ComplaintEvent event(Complaint c,String actor,String type,Complaint.Status old,Complaint.Status next,String note,Instant now) {
    return new Complaint.ComplaintEvent(hash(c.getId()+"\n"+c.getRevision()+"\n"+type),c.getId(),actor,type,old,next,now,note);
  }
  static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  static String blank(String value) { return value==null || value.isBlank()?null:value; }
  static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
  static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Complaint changed or action is not allowed; reload"); }
}
