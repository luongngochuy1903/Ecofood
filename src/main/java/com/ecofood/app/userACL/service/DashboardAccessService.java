package com.ecofood.app.userACL.service;

import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.GroupEntity;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.UserGroupEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.PropositionTenantReference;
import com.ecofood.core.enums.*;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import com.ecofood.core.enums.AccessLevelEnum;
import com.ecofood.core.enums.GroupType;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.UserAccessService;
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
public class DashboardAccessService implements com.ecofood.core.service.DashboardAccessQueryService {
  public static final String READ = "DASHBOARD_READ", PARTNERS = "DASHBOARD_READ_PARTNERS";
  private final MongoTemplate mongo;
  private final UserAccessService users;
  private final CoreTenantQueryClient tenants;
  public com.ecofood.core.dto.DashboardActor current() {
    String userId, tenantId;
    try { userId = AuthenticationContext.getUserId().toHexString(); tenantId = AuthenticationContext.getTenantId().toHexString(); }
    catch (RuntimeException invalid) { throw error(HttpStatus.UNAUTHORIZED, "Invalid Dashboard session"); }
    UserEntity user = mongo.findById(new ObjectId(userId), UserEntity.class);
    if (user == null || Boolean.TRUE.equals(user.getDeleted()) || user.getPartyRelationships() == null
        || user.getPartyRelationships().stream().noneMatch(r -> "TENANT".equals(r.getRelationshipTypeCode()) && new ObjectId(tenantId).equals(r.getToPartyId()))) throw denied();
    var live = users.resolveAccess(user.getId(), new ObjectId(tenantId));
    var tokenRoles = Optional.ofNullable(AuthenticationContext.getRoles()).orElse(List.of());
    if (live.roles().stream().noneMatch(r -> tokenRoles.contains(r) && managerRole(r))) throw denied();
    var grants = restrictions(user.getId(), new ObjectId(tenantId));
    var dashboard = grants.get(AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    var reference = tenants.propositionReference(tenantId);
    if (reference.deleted()) throw denied();
    boolean partners = false;
    Set<String> scope = new HashSet<>(); scope.add(tenantId);
    if (partners && reference.scopedTenantIds() != null) scope.addAll(reference.scopedTenantIds());

    return new com.ecofood.core.dto.DashboardActor(userId, reference, dashboard == null ? Set.of() : Set.copyOf(scope), partners, dashboard == null ? Set.of() : dashboard.full() ? null : dashboard.ids());
  }
  public static boolean managerRole(String role) {
    if (role == null) return false;
    String code = role.toLowerCase(Locale.ROOT);
    return code.endsWith("admin") || code.endsWith("dashboard_manager");
  }
  public com.ecofood.core.dto.DashboardActor current(AccessEntityTypeEnum type) {
    com.ecofood.core.dto.DashboardActor actor = current();
    var grant = restrictions(new ObjectId(actor.userId()), new ObjectId(actor.tenant().id())).get(type);
    return new com.ecofood.core.dto.DashboardActor(actor.userId(), actor.tenant(), grant == null ? Set.of() : Set.of(actor.tenant().id()),
        false, grant == null ? Set.of() : grant.full() ? null : grant.ids());
  }
  public com.ecofood.core.dto.DashboardActor current(AccessEntityTypeEnum type, String tenantId) {
    com.ecofood.core.dto.DashboardActor session = current();
    if (tenantId == null || tenantId.equals(session.tenant().id())) return current(type);
    var target = tenants.propositionReference(tenantId);
    if (target.deleted() || target.relationships() == null || target.relationships().stream().noneMatch(r ->
        "MT".equals(r.getRelationshipTypeCode()) && session.tenant().id().equals(r.getToPartyId()))) throw denied();
    var grant = restrictions(new ObjectId(session.userId()), new ObjectId(tenantId)).get(type);
    return new com.ecofood.core.dto.DashboardActor(session.userId(), target, grant == null ? Set.of() : Set.of(tenantId), false,
        grant == null ? Set.of() : grant.full() ? null : grant.ids());
  }
  public List<com.ecofood.core.dto.DashboardActor> authorizedTenants(AccessEntityTypeEnum type) {
    com.ecofood.core.dto.DashboardActor session = current();
    Set<String> candidates = new LinkedHashSet<>(); candidates.add(session.tenant().id());
    if (session.tenant().scopedTenantIds() != null) candidates.addAll(session.tenant().scopedTenantIds());
    List<com.ecofood.core.dto.DashboardActor> result = new ArrayList<>();
    for (String id : candidates) {
      if (!id.equals(session.tenant().id())) {
        var target = tenants.propositionReference(id);
        if (target.deleted() || target.relationships() == null || target.relationships().stream().noneMatch(r ->
            "MT".equals(r.getRelationshipTypeCode()) && session.tenant().id().equals(r.getToPartyId()))) continue;
      }
      com.ecofood.core.dto.DashboardActor actor = current(type, id);
      if (!actor.tenantScope().isEmpty()) result.add(actor);
    }
    return List.copyOf(result);
  }
  private record Grant(boolean full, Set<String> ids) {}
  private Map<AccessEntityTypeEnum, Grant> restrictions(ObjectId user, ObjectId tenant) {
    Map<AccessEntityTypeEnum, Grant> result = new HashMap<>();
    var membership = mongo.findOne(Query.query(Criteria.where("userIdentifier").is(user).and("deleted").ne(true)), UserGroupEntity.class);
    if (membership == null || membership.getGroups() == null) return result;
    for (var link : membership.getGroups()) {
      var group = scopedGroup(tenant, link.getTenantIdentifier(), link.getGroupIdentifier());
      if (group == null) continue;
      var type = group.getAccessEntityType();
      var old = result.get(type);
      Set<String> ids = new HashSet<>(old == null ? Set.of() : old.ids());
      boolean full = (old != null && old.full()) || group.getAccessLevel() == AccessLevelEnum.FULL_ACCESS;
      if (group.getAccessLevel() == AccessLevelEnum.LIMITED_ACCESS)
        for (var entities : mongo.find(Query.query(Criteria.where("groupIdentifier").is(group.getId()).and("accessEntityType").is(type).and("deleted").ne(true)), GroupEntity.class))
          if (entities.getEntityIds() != null) entities.getEntityIds().forEach(id -> ids.add(id.toHexString()));
      result.put(type, new Grant(full, Set.copyOf(ids)));
    }
    // A combined rule grants each dashboard within this rule's Tenant only.
    var all = result.get(AccessEntityTypeEnum.ALL_DASHBOARDS);
    if (all != null && all.full()) {
      for (var type : List.of(AccessEntityTypeEnum.BUSINESS_DASHBOARD, AccessEntityTypeEnum.INVOICE_DASHBOARD,
          AccessEntityTypeEnum.CUSTOMER_DASHBOARD)) result.put(type, new Grant(true, Set.of()));
    }
    return result;
  }
  private Group scopedGroup(ObjectId tenant, ObjectId linkedTenant, ObjectId groupId) {
    if (!tenant.equals(linkedTenant) || groupId == null) return null;
    var group = mongo.findById(groupId, Group.class);
    if (group == null || Boolean.TRUE.equals(group.getDeleted()) || group.getGroupType() != GroupType.ACCESS_CONTROL_GROUP
        || group.getAccessEntityType() == null || group.getPartyRelationships() == null
        || group.getPartyRelationships().stream().noneMatch(r -> "TENANT".equals(r.getRelationshipTypeCode()) && tenant.equals(r.getToPartyId()))) return null;
    return group;
  }
  public static ResponseStatusException denied() { return error(HttpStatus.FORBIDDEN, "Dashboard permission or Tenant scope denied"); }
  public static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
