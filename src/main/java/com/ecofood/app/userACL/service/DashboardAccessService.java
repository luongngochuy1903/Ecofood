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
  private final DashboardTenantHierarchy hierarchy;
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
    var evaluation = evaluate(type);
    String id = tenantId == null ? evaluation.session().tenant().id() : tenantId;
    if (!evaluation.candidates().contains(id)) throw denied();
    return actor(evaluation, id);
  }
  public List<com.ecofood.core.dto.DashboardActor> authorizedTenants(AccessEntityTypeEnum type) {
    return tenantOptions(type).stream().filter(actor -> !actor.tenantScope().isEmpty()).toList();
  }
  public List<com.ecofood.core.dto.DashboardActor> tenantOptions(AccessEntityTypeEnum type) {
    var evaluation = evaluate(type);
    return evaluation.candidates().stream().map(id -> actor(evaluation, id)).toList();
  }
  public List<com.ecofood.core.dto.DashboardActor> scopedTenants(AccessEntityTypeEnum type, String root) {
    var evaluation = evaluate(type);
    String selected = root == null ? evaluation.session().tenant().id() : root;
    if (!selectionCandidates(evaluation, type).contains(selected)) throw denied();
    return scope(evaluation, selected).entrySet().stream()
        .map(entry -> actor(evaluation, entry.getKey(), entry.getValue())).toList();
  }
  public List<com.ecofood.core.dto.DashboardDtos.Option> selectableTenants(AccessEntityTypeEnum type) {
    var evaluation = evaluate(type);
    return selectionCandidates(evaluation, type).stream().map(id -> {
      var scope = List.copyOf(scope(evaluation, id).keySet());
      var tenant = evaluation.tree().tenants().get(id);
      return new com.ecofood.core.dto.DashboardDtos.Option(id, tenant.name(), !scope.isEmpty(), scope, tenant.partnerType());
    }).toList();
  }
  private Set<String> selectionCandidates(Evaluation evaluation, AccessEntityTypeEnum type) {
    if (type != AccessEntityTypeEnum.BUSINESS_DASHBOARD && type != AccessEntityTypeEnum.INVOICE_DASHBOARD)
      return evaluation.candidates();
    String root = evaluation.session().tenant().id();
    Set<String> result = new LinkedHashSet<>(); result.add(root);
    result.addAll(evaluation.tree().children().getOrDefault(root, Set.of()));
    result.retainAll(evaluation.candidates());
    return result;
  }
  private record Evaluation(com.ecofood.core.dto.DashboardActor session, DashboardTenantHierarchy.Tree tree,
      Set<String> candidates, Map<String, Grant> grants, Map<String, Grant> edges) {}

  private Map<String, Grant> scope(Evaluation evaluation, String selected) {
    if (selected.equals(evaluation.session().tenant().id())) return evaluation.grants();
    if (!evaluation.grants().containsKey(selected)) return Map.of();
    Map<String, Grant> result = new LinkedHashMap<>(); result.put(selected, evaluation.grants().get(selected));
    Deque<String> pending = new ArrayDeque<>(); pending.add(selected);
    while (!pending.isEmpty()) {
      String parent = pending.removeFirst();
      for (String child : evaluation.tree().children().getOrDefault(parent, Set.of())) {
        if (child.equals(selected) || child.equals(evaluation.session().tenant().id())) continue;
        var next = intersect(result.get(parent), evaluation.edges().get(parent + ":" + child));
        if (next == null) continue;
        var merged = union(result.get(child), next);
        if (!merged.equals(result.get(child))) { result.put(child, merged); pending.add(child); }
      }
    }
    return result;
  }

  private Evaluation evaluate(AccessEntityTypeEnum type) {
    var session = current(); // Preserve the live role and session checks before resolving any hierarchy.
    var tree = hierarchy.load(session.tenant());
    String root = session.tenant().id();
    var candidates = tree.descendants(root);
    Map<String, Grant> grants = new HashMap<>();
    var own = restrictions(new ObjectId(session.userId()), new ObjectId(root)).get(type);
    if (own != null) grants.put(root, own);
    Map<String, Grant> paths = new HashMap<>();
    paths.put(root, new Grant(true, Set.of())); // Own-data DAR does not gate grants to direct children.
    Map<String, List<UserEntity>> parentUsers = new HashMap<>();
    Map<String, Grant> edges = new HashMap<>();
    Set<String> checked = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>(); pending.add(root);
    while (!pending.isEmpty()) {
      String parent = pending.removeFirst();
      for (String child : tree.children().getOrDefault(parent, Set.of())) {
        if (child.equals(root)) continue; // Cycles must never manufacture a grant back into the session Tenant.
        String key = parent + ":" + child;
        if (checked.add(key)) {
          Grant edge = null;
          if (parent.equals(root)) edge = restrictions(new ObjectId(session.userId()), new ObjectId(child)).get(type);
          else {
            // DAR has user memberships rather than a source-Tenant field. A downstream edge belongs
            // to an active member of this intermediate Tenant; it is not a direct grant to the root user.
            var members = parentUsers.computeIfAbsent(parent, id -> mongo.find(Query.query(Criteria.where("deleted").ne(true)
                .and("partyRelationships").elemMatch(Criteria.where("relationshipTypeCode").is("TENANT")
                    .and("toPartyId").is(new ObjectId(id)))), UserEntity.class));
            for (var member : members) {
              if (Boolean.TRUE.equals(member.getDeleted()) || member.getId() == null || member.getPartyRelationships() == null
                  || member.getPartyRelationships().stream().noneMatch(r -> "TENANT".equals(r.getRelationshipTypeCode())
                      && new ObjectId(parent).equals(r.getToPartyId()))) continue;
              edge = union(edge, restrictions(member.getId(), new ObjectId(child)).get(type));
            }
          }
          if (edge != null) edges.put(key, edge);
        }
        Grant next = intersect(paths.get(parent), edges.get(key));
        if (next == null) continue;
        Grant merged = union(paths.get(child), next);
        if (!merged.equals(paths.get(child))) {
          paths.put(child, merged); grants.put(child, merged); pending.addLast(child);
        }
      }
    }
    return new Evaluation(session, tree, candidates, grants, edges);
  }
  private com.ecofood.core.dto.DashboardActor actor(Evaluation evaluation, String id) {
    var grant = evaluation.grants().get(id);
    return actor(evaluation, id, grant);
  }
  private com.ecofood.core.dto.DashboardActor actor(Evaluation evaluation, String id, Grant grant) {
    return new com.ecofood.core.dto.DashboardActor(evaluation.session().userId(), evaluation.tree().tenants().get(id),
        grant == null ? Set.of() : Set.of(id), false, grant == null ? Set.of() : grant.full() ? null : grant.ids());
  }
  private static Grant union(Grant a, Grant b) {
    if (a == null) return b;
    if (b == null) return a;
    if (a.full() || b.full()) return new Grant(true, Set.of());
    Set<String> ids = new HashSet<>(a.ids()); ids.addAll(b.ids());
    return new Grant(false, Set.copyOf(ids));
  }
  private static Grant intersect(Grant a, Grant b) {
    if (a == null || b == null) return null;
    if (a.full()) return b;
    if (b.full()) return a;
    Set<String> ids = new HashSet<>(a.ids()); ids.retainAll(b.ids());
    return new Grant(false, Set.copyOf(ids));
  }
  private record Grant(boolean full, Set<String> ids) {}
  private Map<AccessEntityTypeEnum, Grant> restrictions(ObjectId user, ObjectId tenant) {
    Map<AccessEntityTypeEnum, Grant> result = new HashMap<>();
    var membership = mongo.findOne(Query.query(Criteria.where("userIdentifier").is(user).and("deleted").ne(true)), UserGroupEntity.class);
    if (membership == null || membership.getGroups() == null) return result;
    for (var link : membership.getGroups()) {
      var group = scopedGroup(tenant, link.getTenantIdentifier(), link.getGroupIdentifier());
      if (group == null) continue;
      var types = group.getAccessEntityTypes() != null ? group.getAccessEntityTypes() : List.of(group.getAccessEntityType());
      for (var type : types) {
      if (type == null) continue;
      var old = result.get(type);
      Set<String> ids = new HashSet<>(old == null ? Set.of() : old.ids());
      boolean full = (old != null && old.full()) || group.getAccessLevel() == AccessLevelEnum.FULL_ACCESS;
      if (group.getAccessLevel() == AccessLevelEnum.LIMITED_ACCESS)
        for (var entities : mongo.find(Query.query(Criteria.where("groupIdentifier").is(group.getId()).and("accessEntityType").is(type).and("deleted").ne(true)), GroupEntity.class))
          if (entities.getEntityIds() != null) entities.getEntityIds().forEach(id -> ids.add(id.toHexString()));
      result.put(type, new Grant(full, Set.copyOf(ids)));
      }
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
        || (group.getAccessEntityTypes() == null ? group.getAccessEntityType() == null : group.getAccessEntityTypes().isEmpty()) || group.getPartyRelationships() == null
        || group.getPartyRelationships().stream().noneMatch(r -> "TENANT".equals(r.getRelationshipTypeCode()) && tenant.equals(r.getToPartyId()))) return null;
    return group;
  }
  public static ResponseStatusException denied() { return error(HttpStatus.FORBIDDEN, "Dashboard permission or Tenant scope denied"); }
  public static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
