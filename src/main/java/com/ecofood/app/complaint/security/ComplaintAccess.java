package com.ecofood.app.complaint.security;

import com.ecofood.app.complaint.entity.Complaint;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.UserGroupEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.ComplaintDtos;
import com.ecofood.core.enums.*;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import com.ecofood.core.enums.AccessLevelEnum;
import com.ecofood.core.enums.ComplaintTypeEnum;
import com.ecofood.core.enums.GroupType;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.security.AuthenticationContext;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ComplaintAccess {
  public static final String READ="COMPLAINT_READ";
  public static final String MANAGE="COMPLAINT_MANAGE";
  public static final String ASSIGN="COMPLAINT_ASSIGN";
  private final NotificationDirectory directory;
  private final CoreTenantQueryClient tenants;
  private final MongoTemplate mongo;
  public record Actor(String userId, String tenantId, List<ComplaintTypeEnum> types, boolean manage, boolean assign) {
    public void requireManage() { if (!manage) throw denied(); }
  }
  public Actor current() {
    try {
      String id=AuthenticationContext.getUserId().toHexString(), tenant=AuthenticationContext.getTenantId().toHexString();
      var user=directory.user(id,tenant); if(user==null) throw denied();
      var live=directory.claims(user,tenant);
      Set<String> roles=new HashSet<>(live.roles());
      Set<String> permissions=new HashSet<>(live.permissions());
      roles.retainAll(Optional.ofNullable(AuthenticationContext.getRoles()).orElse(List.of()));
      permissions.retainAll(Optional.ofNullable(AuthenticationContext.getPermissions()).orElse(List.of()));
      var reference=tenants.propositionReference(tenant); if(reference.deleted() || !permissions.contains(READ)) throw denied();
      List<ComplaintTypeEnum> types=new ArrayList<>();
      if(roles.contains("TENANT_ADMIN")) types.add(ComplaintTypeEnum.TENANT);
      if(roles.contains("ECO_ADMIN") && reference.partnerType()==PartnerTypeEnum.ECO) types.add(ComplaintTypeEnum.ECOSYSTEM);
      if(types.isEmpty()) throw denied();
      // Existing DAR has no Complaint entity. Honor applicable Customer/Bill restrictions conservatively.
      requireFullRelatedDataAccess(id,tenant);
      return new Actor(id,tenant,List.copyOf(types),permissions.contains(MANAGE),permissions.contains(ASSIGN));
    } catch(ResponseStatusException e) { throw e; } catch(RuntimeException e) { throw denied(); }
  }
  private void requireFullRelatedDataAccess(String id,String tenant) {
    var membership=mongo.findOne(Query.query(Criteria.where("userIdentifier").is(new ObjectId(id)).and("deleted").ne(true)),UserGroupEntity.class);
    if(membership==null || membership.getGroups()==null) return;
    Map<AccessEntityTypeEnum,Boolean> full=new EnumMap<>(AccessEntityTypeEnum.class);
    for(var link:membership.getGroups()) {
      if(!new ObjectId(tenant).equals(link.getTenantIdentifier()) || link.getGroupIdentifier()==null) continue;
      var group=mongo.findById(link.getGroupIdentifier(),Group.class);
      if(group==null || Boolean.TRUE.equals(group.getDeleted()) || group.getGroupType()!=GroupType.ACCESS_CONTROL_GROUP || group.getAccessEntityType()==null
          || !List.of(AccessEntityTypeEnum.Dashboard_bills,AccessEntityTypeEnum.Dashboard_Customer).contains(group.getAccessEntityType())
          || group.getPartyRelationships()==null || group.getPartyRelationships().stream().noneMatch(r -> "TENANT".equals(r.getRelationshipTypeCode()) && new ObjectId(tenant).equals(r.getToPartyId()))) continue;
      full.merge(group.getAccessEntityType(),group.getAccessLevel()==AccessLevelEnum.FULL_ACCESS,(a,b)->a||b);
    }
    if(full.containsValue(false)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Complaint workspace requires full applicable Customer/Invoice data access");
  }
  public boolean eligible(String id,String tenant,ComplaintTypeEnum type) {
    var user=directory.user(id,tenant); if(user==null) return false;
    var claims=directory.claims(user,tenant); String role=type==ComplaintTypeEnum.TENANT?"TENANT_ADMIN":"ECO_ADMIN";
    if(!claims.roles().contains(role) || !claims.permissions().containsAll(List.of(READ,MANAGE))) return false;
    try { requireFullRelatedDataAccess(id,tenant); return type!=ComplaintTypeEnum.ECOSYSTEM || tenants.propositionReference(tenant).partnerType()==PartnerTypeEnum.ECO; }
    catch(RuntimeException denied) { return false; }
  }
  public String ecosystemTenant() {
    var eco=tenants.propositionReferences().stream().filter(t->!t.deleted() && t.partnerType()==PartnerTypeEnum.ECO).toList();
    if(eco.size()!=1) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Exactly one existing ECO Tenant must be configured");
    return eco.get(0).id();
  }
  public List<ComplaintDtos.Assignee> assignees(Actor actor,ComplaintTypeEnum type) {
    if(!actor.types().contains(type)) throw denied();
    return directory.users(actor.tenantId()).stream().filter(u->eligible(u.getIdAsString(),actor.tenantId(),type))
        .map(u->new ComplaintDtos.Assignee(u.getIdAsString(),Objects.toString(u.getFirstName(),"")+" "+Objects.toString(u.getLastName(),""))).toList();
  }
  public void requireScope(Actor actor,Complaint c) {
    if(c.getQueue()==null || !actor.tenantId().equals(c.getQueue().tenantId()) || !actor.types().contains(c.getComplaintType())) throw missing();
  }
  public static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Complaint Admin role, permission and data scope required"); }
  public static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Complaint unavailable"); }
}
