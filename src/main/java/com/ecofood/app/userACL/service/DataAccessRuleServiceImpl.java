package com.ecofood.app.userACL.service;

import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.GroupEntity;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.UserGroupEntity;
import com.ecofood.app.userACL.repository.GroupEntityRepository;
import com.ecofood.app.userACL.repository.GroupRepository;
import com.ecofood.app.userACL.repository.UserGroupEntityRepository;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.CreateDataAccessRuleRequest;
import com.ecofood.core.dto.DataAccessRuleDetailResponse;
import com.ecofood.core.dto.DataAccessRuleDirectoryResponse;
import com.ecofood.core.dto.DataAccessRuleResponse;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.dto.UpdateDataAccessRuleRequest;
import com.ecofood.core.entity.RoleLevel;
import com.ecofood.core.enums.AccessLevelEnum;
import com.ecofood.core.enums.GroupType;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import com.ecofood.core.service.DataAccessRuleService;
import com.ecofood.core.service.SequenceGeneratorService;
import com.ecofood.core.util.FormatCode;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Owns Access Control Group (Data Access Rule) creation — a Group row
 * (groupType = ACCESS_CONTROL_GROUP), its per-entity-type GroupEntity children, and an update to each selected user's own UserGroupEntity
 * document (membership lives on the user's side, not the group's — see
 * UserGroupEntity's javadoc). Deliberately a separate service from
 * GroupService, which only ever seeds ROLE_GROUP rows for ACLService.
 * getMaxLevel() — the two concerns share a collection, not a service.
 *
 * Implements only DataAccessRuleService (plain, same-module-facing) — never
 * CoreDataAccessRuleClient directly. CoreDataAccessRuleClient (common.client)
 * is a separate delegating class other modules call instead, same
 * module-boundary convention as TenantServiceImpl/UserServiceImpl.
 */
@Service
@RequiredArgsConstructor
public class DataAccessRuleServiceImpl implements DataAccessRuleService {

  private static final String GROUP_CODE_PREFIX = "DAR";
  private static final int GROUP_CODE_LENGTH = 4;
  private static final int MAX_DIRECTORY_PAGE_SIZE = 100;
  private static final Set<String> DIRECTORY_SORTABLE_FIELDS = Set.of("updatedAt", "name");
  private static final Set<RoleLevel> ALLOWED_LEVELS =
      Set.of(RoleLevel.SYSTEM, RoleLevel.ENTERPRISE, RoleLevel.MASTER_TENANT, RoleLevel.TENANT);

  private final GroupRepository groupRepository;
  private final GroupEntityRepository groupEntityRepository;
  private final UserGroupEntityRepository userGroupEntityRepository;
  private final UserRepository userRepository;
  private final CoreTenantQueryClient tenantQueryClient;
  private final SequenceGeneratorService sequenceGeneratorService;
  private final ACLService aclService;
  private final DashboardTenantHierarchy hierarchy;

  @Override
  public List<com.ecofood.core.dto.DashboardDtos.Option> eligibleTenants() {
    RoleLevel level = aclService.getMaxLevel();
    if (!ALLOWED_LEVELS.contains(level)) throw new ForbiddenException("Data access rules denied");
    var scope = level == RoleLevel.SYSTEM ? Set.<ObjectId>of() : Set.copyOf(resolveScopedTenantIds(level));
    return tenantQueryClient.propositionReferences().stream().filter(t -> !t.deleted())
        .filter(t -> level == RoleLevel.SYSTEM || scope.contains(new ObjectId(t.id())))
        .sorted(java.util.Comparator.comparing(com.ecofood.core.dto.PropositionTenantReference::name,
            java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
        .map(t -> new com.ecofood.core.dto.DashboardDtos.Option(t.id(), t.name(), true, List.of(t.id()), t.partnerType())).toList();
  }

  private List<ObjectId> eligibleTenantIds(String tenantId) {
    RoleLevel level = aclService.getMaxLevel();
    if (!ALLOWED_LEVELS.contains(level)) throw new ForbiddenException("Data access rules denied");
    if (level != RoleLevel.SYSTEM && !resolveScopedTenantIds(level).contains(new ObjectId(tenantId)))
      throw new ForbiddenException("Tenant is outside caller scope");
    var tenant = tenantQueryClient.propositionReference(tenantId);
    if (tenant.deleted()) throw new BadRequestException("Tenant is deleted");
    var ids = new java.util.LinkedHashSet<ObjectId>(); ids.add(new ObjectId(tenantId));
    if (tenant.relationships() != null) tenant.relationships().stream()
        .filter(r -> "MT".equals(r.getRelationshipTypeCode())).map(r -> r.getToPartyId())
        .filter(java.util.Objects::nonNull).map(ObjectId::new).forEach(ids::add);
    if (tenant.partnerType() == com.ecofood.core.enums.PartnerTypeEnum.REGIONAL_ENTERPRISE)
      hierarchy.directParents(tenant).stream().map(ObjectId::new).forEach(ids::add);
    return List.copyOf(ids);
  }
  @Override public List<com.ecofood.core.dto.UserDirectoryResponse> eligibleUsers(String tenantId) {
    return userRepository.findDirectory(eligibleTenantIds(tenantId), false, null, Pageable.unpaged()).stream()
        .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
        .map(u -> com.ecofood.core.dto.UserDirectoryResponse.builder().id(u.getIdAsString())
            .firstName(u.getFirstName()).lastName(u.getLastName()).email(u.getEmail()).build()).toList();
  }
  private void validateCombinedDashboardRule(com.ecofood.core.enums.AccessEntityTypeEnum type, AccessLevelEnum level) {
    if (type == com.ecofood.core.enums.AccessEntityTypeEnum.ALL_DASHBOARDS && level != AccessLevelEnum.FULL_ACCESS)
      throw new BadRequestException("All Dashboards currently supports Full Access only");
  }
  private List<com.ecofood.core.enums.AccessEntityTypeEnum> selectedTypes(
      List<com.ecofood.core.enums.AccessEntityTypeEnum> types, com.ecofood.core.enums.AccessEntityTypeEnum legacy, AccessLevelEnum level) {
    if (types == null) { validateCombinedDashboardRule(legacy, level); return null; }
    var allowed = Set.of(com.ecofood.core.enums.AccessEntityTypeEnum.BUSINESS_DASHBOARD,
        com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD, com.ecofood.core.enums.AccessEntityTypeEnum.CUSTOMER_DASHBOARD);
    if (types.isEmpty() || types.stream().anyMatch(t -> t == null || !allowed.contains(t)))
      throw new BadRequestException("Select at least one valid dashboard entity type");
    if (level != AccessLevelEnum.FULL_ACCESS) throw new BadRequestException("Dashboard selections currently support Full Access only");
    return List.copyOf(new java.util.LinkedHashSet<>(types));
  }
  private List<com.ecofood.core.enums.AccessEntityTypeEnum> storedTypes(Group group) {
    if (group.getAccessEntityTypes() != null) return group.getAccessEntityTypes();
    return group.getAccessEntityType() == null ? List.of() : List.of(group.getAccessEntityType());
  }
  private List<com.ecofood.core.enums.AccessEntityTypeEnum> responseTypes(Group group) {
    if (group.getAccessEntityTypes() == null && group.getAccessEntityType() == com.ecofood.core.enums.AccessEntityTypeEnum.ALL_DASHBOARDS)
      return List.of(com.ecofood.core.enums.AccessEntityTypeEnum.BUSINESS_DASHBOARD,
          com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD, com.ecofood.core.enums.AccessEntityTypeEnum.CUSTOMER_DASHBOARD);
    return storedTypes(group);
  }
  private void validateUsers(String tenantId, List<String> userIds) {
    var eligible = eligibleUsers(tenantId).stream().map(com.ecofood.core.dto.UserDirectoryResponse::getId).collect(java.util.stream.Collectors.toSet());
    if (userIds != null && !eligible.containsAll(userIds)) throw new BadRequestException("Users must belong to the selected Tenant or its direct parent");
  }
  private String ruleTenant(Group group) {
    return group.getPartyRelationships().stream().filter(r -> "TENANT".equals(r.getRelationshipTypeCode()))
        .map(r -> r.getToPartyId().toHexString()).findFirst().orElseThrow();
  }
  @Override
  public DataAccessRuleResponse create(CreateDataAccessRuleRequest request) {
    if (!ALLOWED_LEVELS.contains(aclService.getMaxLevel())) {
      throw new ForbiddenException("Caller's role level does not grant access to data access rules");
    }

    var types = selectedTypes(request.getAccessEntityTypes(), request.getAccessEntityType(), request.getAccessLevel());
    if (types != null || request.getAccessEntityType() != com.ecofood.core.enums.AccessEntityTypeEnum.Product)
      validateUsers(request.getBusinessEntityId(), request.getUserIds());
    TenantResponse tenant = tenantQueryClient.getById(request.getBusinessEntityId());

    long sequence = sequenceGeneratorService.generateSequence(Group.SEQUENCE_NAME);

    Group group = Group.builder()
            .name(request.getRuleName())
            .code(FormatCode.byPaddingZeros(sequence, GROUP_CODE_LENGTH, GROUP_CODE_PREFIX))
            .groupType(GroupType.ACCESS_CONTROL_GROUP)
            .groupDescription(request.getDescription())
            .accessLevel(request.getAccessLevel())
            .accessEntityType(types == null ? request.getAccessEntityType() : null)
            .accessEntityTypes(types)
            .partyRelationships(List.of(buildTenantRelation(tenant)))
            .build();
    groupRepository.save(group);

    createGroupEntity(group, request);
    assignUsers(group, tenant, request.getUserIds());

    return toResponse(group, request.getUserIds());
  }

  private void createGroupEntity(Group group, CreateDataAccessRuleRequest request) {
    saveGroupEntities(group, request.getLimitedEntityIds());
  }
  private void saveGroupEntities(Group group, List<String> limitedIds) {
    var ids = group.getAccessLevel() != AccessLevelEnum.LIMITED_ACCESS || limitedIds == null
        ? List.<ObjectId>of() : limitedIds.stream().map(ObjectId::new).toList();
    for (var type : storedTypes(group)) groupEntityRepository.save(GroupEntity.builder()
        .groupIdentifier(group.getId()).accessEntityType(type).entityIds(ids).build());
  }

  /** Appends a snapshot of this rule onto each selected user's own UserGroupEntity — never a members[] list on the Group itself. */
  private void assignUsers(Group group, TenantResponse tenant, List<String> userIds) {
    if (userIds == null) {
      return;
    }

    for (String userId : userIds) {
      ObjectId userObjectId = new ObjectId(userId);
      UserEntity user =
          userRepository
              .findById(userObjectId)
              .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

      UserGroupEntity userGroupEntity =
          userGroupEntityRepository
              .findByUserIdentifier(user.getId())
              .orElseGet(() -> UserGroupEntity.builder().userIdentifier(user.getId()).groups(new ArrayList<>()).build());

      boolean alreadyAssigned =
          userGroupEntity.getGroups().stream().anyMatch(g -> group.getId().equals(g.getGroupIdentifier()));
      if (!alreadyAssigned) {
        userGroupEntity
            .getGroups()
            .add(
                UserGroupEntity.EmbeddedRestrictionGroup.builder()
                    .groupIdentifier(group.getId())
                    .groupCode(group.getCode())
                    .groupName(group.getName())
                    .tenantIdentifier(new ObjectId(tenant.getId()))
                    .build());
        userGroupEntityRepository.save(userGroupEntity);
      }
    }
  }

  @Override
  public DataAccessRuleDetailResponse getById(String id) {
    if (!ALLOWED_LEVELS.contains(aclService.getMaxLevel())) {
      throw new ForbiddenException("Caller's role level does not grant access to data access rules");
    }
    return toDetailResponse(loadAccessControlGroup(id));
  }

  /** Only the rule's own creator (Group.createdBy, set by Mongo auditing off the internal token's subject) may update it. */
  @Override
  public DataAccessRuleDetailResponse update(String id, UpdateDataAccessRuleRequest request) {
    Group group = loadAccessControlGroup(id);
    if (!isOwnedByCaller(group)) {
      throw new ForbiddenException("Only the creator of this rule can edit it");
    }

    var types = selectedTypes(request.getAccessEntityTypes(), request.getAccessEntityType(), request.getAccessLevel());
    if (types != null || request.getAccessEntityType() != com.ecofood.core.enums.AccessEntityTypeEnum.Product)
      validateUsers(ruleTenant(group), request.getUserIds());
    group.setName(request.getRuleName());
    group.setGroupDescription(request.getDescription());
    group.setAccessLevel(request.getAccessLevel());
    group.setAccessEntityType(types == null ? request.getAccessEntityType() : null);
    group.setAccessEntityTypes(types);
    groupRepository.save(group);

    updateGroupEntity(group, request);
    reconcileUsers(group, request.getUserIds());

    return toDetailResponse(group);
  }

  @Override
  public void delete(String id) {
    var level = aclService.getMaxLevel();
    if (!ALLOWED_LEVELS.contains(level)) throw new ForbiddenException("Data access rules denied");
    // Allow retrying cleanup of an already-deleted rule after a partial failure.
    Group group = groupRepository.findById(new ObjectId(id))
        .filter(g -> g.getGroupType() == GroupType.ACCESS_CONTROL_GROUP)
        .orElseThrow(() -> new IllegalArgumentException("Data access rule not found: " + id));
    if (!isOwnedByCaller(group)) throw new ForbiddenException("Only the creator of this rule can delete it");
    if (level != RoleLevel.SYSTEM && !resolveScopedTenantIds(level).contains(new ObjectId(ruleTenant(group))))
      throw new ForbiddenException("Tenant is outside caller scope");
    // Readers ignore deleted groups immediately, even if subsequent cleanup needs a retry.
    group.setDeleted(true);
    groupRepository.save(group);
    for (var membership : userGroupEntityRepository.findAllByGroups_GroupIdentifier(group.getId())) {
      if (membership.getGroups() != null) {
        membership.getGroups().removeIf(link -> group.getId().equals(link.getGroupIdentifier()));
        userGroupEntityRepository.save(membership);
      }
    }
    groupEntityRepository.deleteAll(groupEntityRepository.findAllByGroupIdentifier(group.getId()));
  }

  private Group loadAccessControlGroup(String id) {
    Group group =
        groupRepository.findById(new ObjectId(id)).orElseThrow(() -> new IllegalArgumentException("Data access rule not found: " + id));
    if (group.getGroupType() != GroupType.ACCESS_CONTROL_GROUP || Boolean.TRUE.equals(group.getDeleted())) {
      throw new IllegalArgumentException("Data access rule not found: " + id);
    }
    return group;
  }

  private boolean isOwnedByCaller(Group group) {
    return AuthenticationContext.getUserId().equals(group.getCreatedBy());
  }

  /** Replaces scope rows so unchecked dashboard types are revoked on update. */
  private void updateGroupEntity(Group group, UpdateDataAccessRuleRequest request) {
    var existing = groupEntityRepository.findAllByGroupIdentifier(group.getId());
    if (!existing.isEmpty()) groupEntityRepository.deleteAll(existing);
    saveGroupEntities(group, request.getLimitedEntityIds());
  }

  /** Revokes the rule from users no longer in desiredUserIds, grants it to newly-added ones — never touches already-assigned users. */
  private void reconcileUsers(Group group, List<String> desiredUserIds) {
    Set<String> desired = desiredUserIds == null ? Set.of() : Set.copyOf(desiredUserIds);
    List<UserGroupEntity> currentlyAssigned = userGroupEntityRepository.findAllByGroups_GroupIdentifier(group.getId());

    for (UserGroupEntity userGroupEntity : currentlyAssigned) {
      String userId = userGroupEntity.getUserIdentifier().toHexString();
      if (!desired.contains(userId)) {
        userGroupEntity.getGroups().removeIf(embedded -> group.getId().equals(embedded.getGroupIdentifier()));
        userGroupEntityRepository.save(userGroupEntity);
      }
    }

    Set<String> alreadyAssigned =
        Set.copyOf(currentlyAssigned.stream().map(u -> u.getUserIdentifier().toHexString()).toList());
    ObjectId tenantId =
        group.getPartyRelationships().stream()
            .filter(relation -> RelationshipTypeEnum.TENANT.getCode().equals(relation.getRelationshipTypeCode()))
            .map(PartyToPartyRelationship::getToPartyId)
            .findFirst()
            .orElse(null);

    for (String userId : desired) {
      if (alreadyAssigned.contains(userId)) {
        continue;
      }
      ObjectId userObjectId = new ObjectId(userId);
      UserEntity user =
          userRepository
              .findById(userObjectId)
              .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

      UserGroupEntity userGroupEntity =
          userGroupEntityRepository
              .findByUserIdentifier(user.getId())
              .orElseGet(() -> UserGroupEntity.builder().userIdentifier(user.getId()).groups(new ArrayList<>()).build());
      userGroupEntity
          .getGroups()
          .add(
              UserGroupEntity.EmbeddedRestrictionGroup.builder()
                  .groupIdentifier(group.getId())
                  .groupCode(group.getCode())
                  .groupName(group.getName())
                  .tenantIdentifier(tenantId)
                  .build());
      userGroupEntityRepository.save(userGroupEntity);
    }
  }

  private DataAccessRuleDetailResponse toDetailResponse(Group group) {
    String businessEntityId =
        group.getPartyRelationships().stream()
            .filter(relation -> RelationshipTypeEnum.TENANT.getCode().equals(relation.getRelationshipTypeCode()))
            .map(relation -> relation.getToPartyId().toHexString())
            .findFirst()
            .orElse(null);
    String businessEntityName =
        group.getPartyRelationships().stream()
            .filter(relation -> RelationshipTypeEnum.TENANT.getCode().equals(relation.getRelationshipTypeCode()))
            .map(PartyToPartyRelationship::getRelationshipReferenceName)
            .findFirst()
            .orElse(null);

    List<String> limitedEntityIds =
        groupEntityRepository.findAllByGroupIdentifier(group.getId()).stream()
            .flatMap(groupEntity -> groupEntity.getEntityIds().stream())
            .map(ObjectId::toHexString)
            .toList();

    List<String> userIds =
        userGroupEntityRepository.findAllByGroups_GroupIdentifier(group.getId()).stream()
            .map(userGroupEntity -> userGroupEntity.getUserIdentifier().toHexString())
            .toList();

    return DataAccessRuleDetailResponse.builder()
        .id(group.getIdAsString())
        .code(group.getCode())
        .ruleName(group.getName())
        .description(group.getGroupDescription())
        .businessEntityId(businessEntityId)
        .businessEntityName(businessEntityName)
        .accessLevel(group.getAccessLevel())
        .accessEntityType(group.getAccessEntityType())
        .accessEntityTypes(responseTypes(group))
        .limitedEntityIds(limitedEntityIds)
        .userIds(userIds)
        .editable(isOwnedByCaller(group))
        .build();
  }

  @Override
  public PageResponse<DataAccessRuleDirectoryResponse> listDirectory(int page, int size, String sort, String search) {
    RoleLevel level = aclService.getMaxLevel();
    boolean unrestricted = level == RoleLevel.SYSTEM;
    if (!unrestricted
        && level != RoleLevel.ENTERPRISE
        && level != RoleLevel.MASTER_TENANT
        && level != RoleLevel.TENANT) {
      throw new ForbiddenException("Caller's role level does not grant access to data access rules");
    }

    List<ObjectId> scopedTenantIds = unrestricted ? List.of() : resolveScopedTenantIds(level);
    Pageable pageable =
        PageRequest.of(page, Math.min(Math.max(size, 1), MAX_DIRECTORY_PAGE_SIZE), resolveDirectorySort(sort));
    Page<Group> result =
        groupRepository.findDirectory(GroupType.ACCESS_CONTROL_GROUP, scopedTenantIds, unrestricted, search, pageable);

    return PageResponse.<DataAccessRuleDirectoryResponse>builder()
        .content(result.getContent().stream().map(this::toDirectoryResponse).toList())
        .page(result.getNumber())
        .size(result.getSize())
        .totalElements(result.getTotalElements())
        .totalPages(result.getTotalPages())
        .build();
  }

  /**
   * TENANT-level never queries the tenant module at all — its scope is
   * always just its own tenant id (same reasoning as UserServiceImpl.
   * resolveScopedTenantIds). ENTERPRISE/MASTER_TENANT delegate to
   * CoreTenantQueryClient for MT children, adding direct OW/BL Regional children
   * for DAR only. Grandchildren still require configuration by their own parent.
   */
  private List<ObjectId> resolveScopedTenantIds(RoleLevel level) {
    ObjectId callerTenantId = AuthenticationContext.getTenantId();
    if (level == RoleLevel.TENANT) {
      return List.of(callerTenantId);
    }
    var ids = new java.util.LinkedHashSet<ObjectId>(); ids.add(callerTenantId);
    tenantQueryClient.getScopedTenantIds(callerTenantId.toHexString()).stream().map(ObjectId::new).forEach(ids::add);
    hierarchy.directRegionalChildren(callerTenantId.toHexString()).stream().map(ObjectId::new).forEach(ids::add);
    return List.copyOf(ids);
  }

  private Sort resolveDirectorySort(String sort) {
    if (sort == null || sort.isBlank()) {
      return Sort.by(Sort.Direction.DESC, "updatedAt");
    }

    String[] parts = sort.split(",", 2);
    String field = parts[0].trim();
    if (!DIRECTORY_SORTABLE_FIELDS.contains(field)) {
      throw new BadRequestException("Unsupported sort field: " + field);
    }

    Sort.Direction direction =
        parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
    return Sort.by(direction, field);
  }

  /** businessEntityName is read directly off the Group's own denormalized TENANT relation — never an extra tenant lookup. */
  private DataAccessRuleDirectoryResponse toDirectoryResponse(Group group) {
    String businessEntityName =
        group.getPartyRelationships().stream()
            .filter(relation -> RelationshipTypeEnum.TENANT.getCode().equals(relation.getRelationshipTypeCode()))
            .map(PartyToPartyRelationship::getRelationshipReferenceName)
            .findFirst()
            .orElse(null);

    return DataAccessRuleDirectoryResponse.builder()
        .id(group.getIdAsString())
        .code(group.getCode())
        .ruleName(group.getName())
        .businessEntityName(businessEntityName)
        .accessLevel(group.getAccessLevel())
        .build();
  }

  private PartyToPartyRelationship buildTenantRelation(TenantResponse tenant) {
    return PartyToPartyRelationship.builder()
        .toPartyId(new ObjectId(tenant.getId()))
        .relationshipTypeCode(RelationshipTypeEnum.TENANT.getCode())
        .relationshipReferenceName(tenant.getTenantName())
        .build();
  }

  private DataAccessRuleResponse toResponse(Group group, List<String> userIds) {
    return DataAccessRuleResponse.builder()
        .id(group.getIdAsString())
        .code(group.getCode())
        .ruleName(group.getName())
        .description(group.getGroupDescription())
        .businessEntityId(
            group.getPartyRelationships().stream()
                .filter(relation -> RelationshipTypeEnum.TENANT.getCode().equals(relation.getRelationshipTypeCode()))
                .map(relation -> relation.getToPartyId().toHexString())
                .findFirst()
                .orElse(null))
        .accessLevel(group.getAccessLevel())
        .accessEntityType(group.getAccessEntityType())
        .accessEntityTypes(responseTypes(group))
        .assignedUserCount(userIds == null ? 0 : userIds.size())
        .build();
  }
}
