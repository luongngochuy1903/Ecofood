package com.ecofood.app.dashboard.security;

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
public class DashboardAccess {
  public static final String READ = "DASHBOARD_READ", PARTNERS = "DASHBOARD_READ_PARTNERS";
  private final MongoTemplate mongo;
  private final UserAccessService users;
  private final CoreTenantQueryClient tenants;
  public record Actor(String userId, PropositionTenantReference tenant, Set<String> tenantScope,
                      boolean partners, Set<String> productIds) {}
  public Actor current() {
    String userId, tenantId;
    try { userId = AuthenticationContext.getUserId().toHexString(); tenantId = AuthenticationContext.getTenantId().toHexString(); }
    catch (RuntimeException invalid) { throw error(HttpStatus.UNAUTHORIZED, "Invalid Dashboard session"); }
    UserEntity user = mongo.findById(new ObjectId(userId), UserEntity.class);
    if (user == null || Boolean.TRUE.equals(user.getDeleted()) || user.getPartyRelationships() == null
        || user.getPartyRelationships().stream().noneMatch(r -> "TENANT".equals(r.getRelationshipTypeCode()) && new ObjectId(tenantId).equals(r.getToPartyId()))) throw denied();
    var live = users.resolveAccess(user.getId(), new ObjectId(tenantId));
    Set<String> permissions = new HashSet<>(live.permissions());
    permissions.retainAll(Optional.ofNullable(AuthenticationContext.getPermissions()).orElse(List.of()));
    if (!permissions.contains(READ)) throw denied();
    var tokenRoles = Optional.ofNullable(AuthenticationContext.getRoles()).orElse(List.of());
    boolean admin = List.of("TENANT_ADMIN", "MASTER_ADMIN", "ENTERPRISE_ADMIN", "ECO_ADMIN").stream()
        .anyMatch(r -> live.roles().contains(r) && tokenRoles.contains(r));
    var grants = restrictions(user.getId(), new ObjectId(tenantId));
    var dashboard = grants.get(AccessEntityTypeEnum.Dashboard_bills);
    if (dashboard == null ? !admin : !dashboard.full())
throw error(HttpStatus.FORBIDDEN, "Dashboard aggregate requires full Dashboard_bills data access; Invoice-level restrictions cannot be applied to monthly aggregates");
    var reference = tenants.propositionReference(tenantId);
    if (reference.deleted()) throw denied();
    boolean enterprise = reference.partnerType() == PartnerTypeEnum.ENTERPRISE || reference.partnerType() == PartnerTypeEnum.REGIONAL_ENTERPRISE;
    boolean partners = enterprise && permissions.contains(PARTNERS);
    Set<String> scope = new HashSet<>(); scope.add(tenantId);
    if (partners && reference.scopedTenantIds() != null) scope.addAll(reference.scopedTenantIds());
    var products = grants.get(AccessEntityTypeEnum.Product);
    return new Actor(userId, reference, Set.copyOf(scope), partners, products == null || products.full() ? null : Set.copyOf(products.ids()));
  }
  private record Grant(boolean full, Set<String> ids) {}
  private Map<AccessEntityTypeEnum, Grant> restrictions(ObjectId user, ObjectId tenant) {
    Map<AccessEntityTypeEnum, Grant> result = new HashMap<>();
    var membership = mongo.findOne(Query.query(Criteria.where("userIdentifier").is(user).and("deleted").ne(true)), UserGroupEntity.class);
    if (membership == null || membership.getGroups() == null) return result;
    for (var link : membership.getGroups()) {
      var group = scopedGroup(tenant, link.getTenantIdentifier(), link.getGroupIdentifier());
      if (group == null) continue;
      var type = group.getAccessEntityType(); var old = result.get(type);
      Set<String> ids = new HashSet<>(old == null ? Set.of() : old.ids());
      boolean full = (old != null && old.full()) || group.getAccessLevel() == AccessLevelEnum.FULL_ACCESS;
      if (group.getAccessLevel() == AccessLevelEnum.LIMITED_ACCESS)
        for (var entities : mongo.find(Query.query(Criteria.where("groupIdentifier").is(group.getId()).and("accessEntityType").is(type).and("deleted").ne(true)), GroupEntity.class))
          if (entities.getEntityIds() != null) entities.getEntityIds().forEach(id -> ids.add(id.toHexString()));
      result.put(type, new Grant(full, Set.copyOf(ids)));
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
