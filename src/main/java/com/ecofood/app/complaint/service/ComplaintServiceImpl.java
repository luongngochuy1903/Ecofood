package com.ecofood.app.complaint.service;

import static com.ecofood.core.dto.ComplaintDtos.*;

import com.ecofood.app.complaint.entity.Complaint;
import com.ecofood.app.complaint.security.ComplaintAccess;
import com.ecofood.app.customer.security.CustomerAccess;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.client.CoreCustomerInvoiceClient;
import com.ecofood.core.dto.ComplaintDtos;
import com.ecofood.core.dto.CustomerInvoiceReference;
import com.ecofood.core.enums.ComplaintStatusEnum;
import com.ecofood.core.enums.ComplaintTypeEnum;
import com.ecofood.core.service.ComplaintService;
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
public class ComplaintServiceImpl implements ComplaintService {
  private static final String REQUIRED_REVISION_AND_NOTE = "Current revision and note are required";
  private static final String CUSTOMER_ID = "customerId";
  private static final String QUEUE_TENANT_ID = "queue.tenantId";
  private static final int CUSTOMER_COMPLAINT_LIMIT = 3;
  private final MongoTemplate mongo;
  private final CustomerAccess customers;
  private final CoreCustomerInvoiceClient invoices;
  private final ComplaintAccess admins;
  private final ComplaintHandoff handoff;
  private final NotificationDirectory directory;
  @Override
  public Complaint create(Create input) {
    var actor=customers.current(); validate(input);
    String id=hash("complaint\n"+actor.userId()+"\n"+actor.tenantId()+"\n"+input.requestId());
    var existing=mongo.findById(id,Complaint.class);
    if(existing!=null) return retry(actor,input,existing);
    CustomerInvoiceReference invoice=null;
    // Even optional ECOSYSTEM invoice references must be owned; no arbitrary Invoice disclosure.
    if(blank(input.invoiceId())!=null) invoice=invoices.requireOwned(actor,input.invoiceId());
    if(input.complaintType()==ComplaintTypeEnum.TENANT && invoice==null) throw bad("TENANT Complaint requires an owned Invoice");
    String target=input.complaintType()==ComplaintTypeEnum.TENANT?invoice.getTenantId():admins.ecosystemTenant();
    var c=new Complaint(); c.setId(id); c.setComplaintType(input.complaintType()); c.setCustomerId(actor.userId()); c.setCustomerTenantId(actor.tenantId());
    c.setInvoiceId(invoice==null?null:invoice.getId()); c.setTenantId(input.complaintType()==ComplaintTypeEnum.TENANT?target:null);
    c.setQueue(new ComplaintDtos.Queue(target,input.complaintType()==ComplaintTypeEnum.TENANT?"TENANT_ADMIN":"ECO_ADMIN"));
    c.setSubject(input.subject().trim()); c.setDescription(input.description().trim()); c.setStatus(ComplaintStatusEnum.SUBMITTED);
    c.setCreatedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); c.setUpdatedAt(c.getCreatedAt()); c.setRevision(1);
    c.setEvents(List.of(event(c,actor.userId(),"CREATED",null,c.getStatus(),"Complaint submitted",c.getCreatedAt())));
    c.setHandoffs(List.of(new Complaint.Handoff(submission(c,"COMPLAINT_CREATED",c.getCreatedAt()),true)));
    // Release quota metadata retained by completed complaints from the previous lifetime limit.
    // Keep the existing unique slot index and every historical complaint/event unchanged.
    mongo.updateMulti(Query.query(Criteria.where(CUSTOMER_ID).is(actor.userId())
        .and("status").in(ComplaintStatusEnum.RESOLVED,ComplaintStatusEnum.CLOSED)
        .and("customerComplaintSlot").exists(true)),new Update().unset("customerComplaintSlot"),Complaint.class);
    for(int attempt=0;attempt<CUSTOMER_COMPLAINT_LIMIT;attempt++) {
      var previous=mongo.find(Query.query(Criteria.where(CUSTOMER_ID).is(actor.userId())
          .and("status").in(ComplaintStatusEnum.SUBMITTED,ComplaintStatusEnum.IN_PROGRESS,ComplaintStatusEnum.WAITING_REPORTER))
          .limit(CUSTOMER_COMPLAINT_LIMIT+1),Complaint.class);
      var repeated=previous.stream().filter(row->id.equals(row.getId())).findFirst();
      if(repeated.isPresent()) return retry(actor,input,repeated.get());
      if(previous.size()>=CUSTOMER_COMPLAINT_LIMIT) throw complaintLimit();
      // Reserve capacity for historical active rows without a slot, without deleting those rows.
      long legacy=previous.stream().filter(row->row.getCustomerComplaintSlot()==null).count();
      Set<Integer> used=new HashSet<>();
      previous.stream().map(Complaint::getCustomerComplaintSlot).filter(Objects::nonNull).forEach(used::add);
      int slot=(int)legacy+1;
      while(used.contains(slot)) slot++;
      if(slot>CUSTOMER_COMPLAINT_LIMIT) throw complaintLimit();
      c.setCustomerComplaintSlot(slot);
      try {
        mongo.insert(c); handoff.deliver(c); return c;
      } catch(DuplicateKeyException race) {
        var winner=mongo.findById(id,Complaint.class);
        if(winner!=null) return retry(actor,input,winner);
        // Only a collision on this account's quota slot is safe to retry.
        boolean quotaRace=race.getMessage()!=null && race.getMessage().contains("uq_complaint_customer_slot");
        if(!quotaRace && !mongo.exists(Query.query(Criteria.where(CUSTOMER_ID).is(actor.userId()).and("customerComplaintSlot").is(slot)),Complaint.class)) {
          throw new ResponseStatusException(HttpStatus.CONFLICT,"Complaint storage constraint conflict; check complaint collection indexes");
        }
      }
    }
    throw complaintLimit();
  }
  private static ResponseStatusException complaintLimit() {
    return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Each account can have a maximum of 3 active complaints at a time");
  }
  private Complaint retry(CustomerAccess.Actor actor,Create input,Complaint existing) {
    if(!actor.userId().equals(existing.getCustomerId()) || !actor.tenantId().equals(existing.getCustomerTenantId())
        || existing.getComplaintType()!=input.complaintType() || !Objects.equals(existing.getInvoiceId(),blank(input.invoiceId()))
        || !existing.getSubject().equals(input.subject().trim()) || !existing.getDescription().equals(input.description().trim())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"requestId was already used for a different complaint; start a new complaint");
    }
    handoff.deliver(existing); return existing;
  }
  @Override
  public Page<Complaint> customerList(ComplaintStatusEnum status,int page,int size) {
    var actor=customers.current(); return list(Criteria.where(CUSTOMER_ID).is(actor.userId()).and("customerTenantId").is(actor.tenantId()),status,page,size);
  }
  @Override
  public Complaint customerDetail(String id) { return customerFind(customers.current(),id); }
  private Complaint customerFind(CustomerAccess.Actor actor,String id) {
    var c=mongo.findOne(Query.query(Criteria.where("_id").is(id).and(CUSTOMER_ID).is(actor.userId()).and("customerTenantId").is(actor.tenantId())),Complaint.class);
    if(c==null) {
      throw ComplaintAccess.missing();
    }
    return c;
  }
  @Override
  public Complaint reply(String id,Reply input) {
    var actor=customers.current();
    var c=customerFind(actor,id);
    if (input == null) throw bad(REQUIRED_REVISION_AND_NOTE);
    check(input.revision(),input.note(),c);
    if(c.getStatus()!=ComplaintStatusEnum.WAITING_REPORTER) throw conflict();
    return write(c,actor.userId(),"REPORTER_REPLIED",ComplaintStatusEnum.IN_PROGRESS,input.note(),new Update(),null);
  }
  @Override
  public Capabilities capabilities() { var a=admins.current(); return new Capabilities(a.tenantId(),a.types(),a.manage(),a.assign()); }
  @Override
  public List<Assignee> assignees(ComplaintTypeEnum type) { return admins.assignees(admins.current(),type); }
  @Override
  public Page<Complaint> adminList(ComplaintTypeEnum type,ComplaintStatusEnum status,int page,int size) {
    var a=admins.current(); if(type!=null && !a.types().contains(type)) throw ComplaintAccess.denied();
    var scope=Criteria.where(QUEUE_TENANT_ID).is(a.tenantId()).and("complaintType").in(type==null?a.types():List.of(type));
    if(!a.administrator()) scope.and("assignedTo").is(a.userId());
    return list(scope,status,page,size);
  }
  @Override
  public Detail adminDetail(String id) {
    var a=admins.current(); var c=adminFind(a,id);
    var u=directory.user(c.getCustomerId(),c.getCustomerTenantId());
    String name=u==null?null:Objects.toString(u.getFirstName(),"")+" "+Objects.toString(u.getLastName(),"");
    return new Detail(c,name,handling(a,c));
  }
  private Complaint adminFind(ComplaintAccess.Actor a,String id) {
    var c=mongo.findOne(Query.query(Criteria.where("_id").is(id).and(QUEUE_TENANT_ID).is(a.tenantId()).and("complaintType").in(a.types())),Complaint.class);
    if(c==null) {
      throw ComplaintAccess.missing();
    }
    admins.requireScope(a,c);
    if(!a.administrator() && !a.userId().equals(c.getAssignedTo())) throw ComplaintAccess.missing();
    return c;
  }
  private Handling handling(ComplaintAccess.Actor actor,Complaint c) {
    boolean manage=actor.manage() && ownsHandling(actor,c);
    boolean active=c.getStatus()!=ComplaintStatusEnum.RESOLVED && c.getStatus()!=ComplaintStatusEnum.CLOSED;
    return new Handling(manage && c.getStatus()==ComplaintStatusEnum.SUBMITTED,
        manage && (c.getStatus()==ComplaintStatusEnum.IN_PROGRESS || c.getStatus()==ComplaintStatusEnum.WAITING_REPORTER),
        manage && active && actor.administrator() && actor.assign(),
        manage && active && c.getAssignedTo()!=null && !actor.userId().equals(c.getAdminOwnerId()),
        manage?Arrays.stream(ComplaintStatusEnum.values()).filter(next->allowed(c.getStatus(),next)).toList():List.of());
  }
  private boolean ownsHandling(ComplaintAccess.Actor actor,Complaint c) {
    return actor.userId().equals(c.getAssignedTo()) || (c.getAssignedTo()==null && actor.administrator());
  }
  @Override
  public Complaint action(String id,String action,Action input) {
    var a=admins.current();
    a.requireManage();
    var c=adminFind(a,id);
    if (input == null) throw bad(REQUIRED_REVISION_AND_NOTE);
    check(input.revision(),input.note(),c);
    if(!ownsHandling(a,c)) throw ComplaintAccess.denied();
    var rights=handling(a,c);
    ComplaintStatusEnum next=c.getStatus(); Update update=new Update(); String event; String note=input.note();
    if(!"assign".equals(action) && blank(input.assignedTo())!=null) throw bad("Assignee only accepted by assign action");
    if(!"transition".equals(action) && input.status()!=null) throw bad("Status only accepted by transition action");
    switch(action) {
      case "accept" -> {
        if(c.getStatus()!=ComplaintStatusEnum.SUBMITTED) throw conflict();
        next=ComplaintStatusEnum.IN_PROGRESS; update.set("assignedTo",a.userId()); event="ACCEPTED";
        if(a.administrator() && c.getAdminOwnerId()==null) update.set("adminOwnerId",a.userId());
        note="Assignee: "+Objects.toString(c.getAssignedTo(),"UNASSIGNED")+" -> "+a.userId()+"\n"+note;
      }
      case "assign" -> {
        if(c.getStatus()==ComplaintStatusEnum.RESOLVED || c.getStatus()==ComplaintStatusEnum.CLOSED) throw conflict();
        if(!rights.assign()) throw ComplaintAccess.denied();
        if(blank(input.assignedTo())==null || !admins.eligibleHandler(input.assignedTo(),a.tenantId(),c.getComplaintType())) throw bad("Assignee must be an authorized handler in this queue");
        update.set("assignedTo",input.assignedTo()).set("adminOwnerId",a.userId()); event="ASSIGNED";
        if(c.getStatus()==ComplaintStatusEnum.SUBMITTED) next=ComplaintStatusEnum.IN_PROGRESS;
        note="Assignee: "+Objects.toString(c.getAssignedTo(),"UNASSIGNED")+" -> "+input.assignedTo()+"\n"+note;
      }
      case "respond" -> {
        if(c.getStatus()!=ComplaintStatusEnum.IN_PROGRESS && c.getStatus()!=ComplaintStatusEnum.WAITING_REPORTER) throw conflict();
        event="ADMIN_REPLIED";
      }
      case "transition" -> {
        next=input.status(); if(!allowed(c.getStatus(),next)) throw conflict();
        event=next==ComplaintStatusEnum.RESOLVED?"RESOLVED":next==ComplaintStatusEnum.CLOSED?"CLOSED":"STATUS_CHANGED";
      }
      case "request-support" -> {
        if(!rights.requestSupport()) throw ComplaintAccess.denied();
        String admin=admins.supportAdmin(c);
        next=ComplaintStatusEnum.IN_PROGRESS;
        update.set("assignedTo",admin).set("adminOwnerId",admin); event="SUPPORT_REQUESTED";
        note="Support requested; reassigned to admin: "+admin+"\n"+note;
      }
      default -> throw bad("Unsupported Complaint action");
    }
    return write(c,a.userId(),event,next,note,update,next==ComplaintStatusEnum.RESOLVED?"COMPLAINT_RESOLVED":null);
  }
  static boolean allowed(ComplaintStatusEnum from,ComplaintStatusEnum to) {
    if(to==null) return false;
    return switch(from) {
      case IN_PROGRESS -> to==ComplaintStatusEnum.WAITING_REPORTER || to==ComplaintStatusEnum.RESOLVED;
      case WAITING_REPORTER -> to==ComplaintStatusEnum.IN_PROGRESS;
      case RESOLVED -> to==ComplaintStatusEnum.CLOSED;
      default -> false;
    };
  }
  private Complaint write(Complaint c,String actor,String type,ComplaintStatusEnum next,String note,Update update,String businessEvent) {
    Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    update.set("status",next).set("updatedAt",now).inc("revision",1).push("events",event(c,actor,type,c.getStatus(),next,note.trim(),now));
    if(next==ComplaintStatusEnum.RESOLVED && c.getStatus()!=next) update.set("resolvedAt",now);
    if(next==ComplaintStatusEnum.CLOSED) update.set("closedAt",now);
    if(next==ComplaintStatusEnum.RESOLVED || next==ComplaintStatusEnum.CLOSED) update.unset("customerComplaintSlot");
    if(businessEvent!=null) update.push("handoffs",new Complaint.Handoff(submission(c,businessEvent,now),true));
    var result=mongo.findAndModify(Query.query(Criteria.where("_id").is(c.getId()).and("revision").is(c.getRevision()).and("status").is(c.getStatus())
        .and(CUSTOMER_ID).is(c.getCustomerId()).and(QUEUE_TENANT_ID).is(c.getQueue().tenantId())),update,FindAndModifyOptions.options().returnNew(true),Complaint.class);
    if(result==null) {
      throw conflict();
    }
    handoff.deliver(result);
    return result;
  }
  private Page<Complaint> list(Criteria scope,ComplaintStatusEnum status,int page,int size) {
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
    if(input.complaintType()==ComplaintTypeEnum.TENANT && blank(input.invoiceId())==null) throw bad("TENANT Complaint requires invoiceId");
    if(input.invoiceId()!=null && input.invoiceId().length()>100) throw bad("Invalid Invoice reference");
  }
  private void check(Long revision,String note,Complaint c) {
    if(revision==null || note==null || note.isBlank() || note.length()>4000) throw bad(REQUIRED_REVISION_AND_NOTE);
    if(revision!=c.getRevision()) throw conflict();
    if(c.getEvents()!=null && c.getEvents().size()>=1000) throw new ResponseStatusException(HttpStatus.CONFLICT,"Complaint audit limit reached; contact support");
  }
  public static Submission submission(Complaint c,String event,Instant now) {
    return new Submission(hash(c.getId()+"\n"+event),event,now,c.getQueue().tenantId(),"COMPLAINT",c.getId(),null,null,null,0);
  }
  private ComplaintDtos.ComplaintEvent event(Complaint c,String actor,String type,ComplaintStatusEnum old,ComplaintStatusEnum next,String note,Instant now) {
    return new ComplaintDtos.ComplaintEvent(hash(c.getId()+"\n"+c.getRevision()+"\n"+type),c.getId(),actor,type,old,next,now,note);
  }
  public static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  static String blank(String value) { return value==null || value.isBlank()?null:value; }
  static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
  static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Complaint changed or action is not allowed; reload"); }
}
