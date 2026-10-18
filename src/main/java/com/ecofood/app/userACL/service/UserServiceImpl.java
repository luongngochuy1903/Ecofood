package com.ecofood.app.userACL.service;

import com.ecofood.core.client.CoreEnterpriseQueryClient;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.AssignTenantRolesRequest;
import com.ecofood.core.dto.AssignTenantRolesResponse;
import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.dto.TenantAssignmentRequest;
import com.ecofood.core.dto.TenantAssignmentResponse;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.dto.TenantRoleAssignmentRequest;
import com.ecofood.core.dto.TenantRoleAssignmentResult;
import com.ecofood.core.dto.UserCreateRequest;
import com.ecofood.core.dto.UserResponse;
import com.ecofood.core.service.UserService;
import com.ecofood.app.tenant.service.TenantKeycloakService;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.core.enums.UserCategoryEnum;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.UserRole;
import com.ecofood.app.userACL.repository.RoleRepository;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.app.userACL.repository.UserRoleRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

/**
 * Owns the actual Keycloak-user-creation logic — called directly by
 * POST /api/v1/users and internally by TenantServiceImpl's onboarding flow
 * (cross-module, via CoreUserClient), so a user is only ever mirrored into
 * Keycloak from this one place.
 *
 * A user can be assigned to MULTIPLE tenants in one call (e.g. an agency
 * admin managing several client tenants), but gets exactly ONE Keycloak
 * identity — mirrored into the FIRST assignment's tenant realm only — same
 * as DCMP's real model, confirmed by reading its actual switch-tenant flow
 * (Service_Auth's SwitchTenantAuthServiceImpl): a user never gets a second
 * Keycloak login just because they have role assignments in another tenant;
 * "switching tenant" there is purely a re-mint of DCMP's own JWT from the
 * same partyRelationships list. Every other assignment here is a pure
 * authorization record (PartyToPartyRelationship + roles), with no Keycloak
 * user of its own.
 *
 * Implements only UserService (plain, same-module-facing) — never
 * CoreUserClient directly. CoreUserClient (common.client) is a separate
 * delegating class that depends on UserService and forwards to it, so other
 * modules call the client, never this bean's concrete type.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

  private static final String REQUIRED_ACTION_VERIFY_EMAIL = "VERIFY_EMAIL";
  private static final String REQUIRED_ACTION_UPDATE_PASSWORD = "UPDATE_PASSWORD";

  private final CoreTenantQueryClient tenantQueryClient;
  private final CoreEnterpriseQueryClient enterpriseQueryClient;
  private final TenantKeycloakService tenantKeycloakService;
  private final UserKeycloakService userKeycloakService;
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;

  @Override
  public UserResponse createUser(UserCreateRequest request) {
    UserCategoryEnum userCategory =
        request.getUserCategoryCode() != null ? request.getUserCategoryCode() : UserCategoryEnum.CUSTOMER;

    UserEntity user =
        UserEntity.builder()
            .username(request.getUsername())
            .email(request.getEmail())
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .userCategory(
                UserCategory.builder()
                    .userCategoryCode(userCategory.getCode())
                    .userCategoryDescription(userCategory.getDescription())
                    .build())
            .build();
    userRepository.save(user);

    List<PartyToPartyRelationship> relations = new ArrayList<>();
    List<TenantAssignmentResponse> assignmentResponses = new ArrayList<>();
    Set<String> seenTenantIds = new HashSet<>();
    Set<String> seenEnterpriseIds = new HashSet<>();

    for (int i = 0; i < request.getTenantAssignments().size(); i++) {
      TenantAssignmentRequest assignment = request.getTenantAssignments().get(i);
      boolean isPrimary = i == 0;

      // A repeated tenantId in the same request would otherwise add a
      // duplicate PartyToPartyRelationship (and a duplicate
      // TenantAssignmentResponse entry) for the same tenant.
      if (!seenTenantIds.add(assignment.getTenantId())) {
        continue;
      }

      TenantResponse tenant = tenantQueryClient.getById(assignment.getTenantId());

      RelationshipTypeEnum relationshipType =
          assignment.getRelationshipTypeCode() != null ? assignment.getRelationshipTypeCode() : RelationshipTypeEnum.TENANT;

      String externalLoginId = null;
      if (isPrimary) {
        // Mirror into the primary tenant's realm only — this user's one and
        // only Keycloak identity. Every other assignment below stays a pure
        // authorization record with no Keycloak login of its own.
        RealmResource realmResource = tenantKeycloakService.realm(tenant.getRealm());
        externalLoginId = userKeycloakService.createUser(realmResource, buildKeycloakUser(user, tenant.getId()));
        // Email delivery (e.g. unconfigured/misconfigured SMTP) is a
        // separate concern from account creation — don't fail the whole
        // request just because the welcome/verify-email couldn't be sent.
        try {
          userKeycloakService.sendVerifyEmail(realmResource, externalLoginId);
        } catch (RuntimeException ex) {
          log.warn("Failed to send verify email for Keycloak user {}: {}", externalLoginId, ex.getMessage());
        }
        user.setExternalLoginId(externalLoginId);
      }

      relations.add(buildRelation(tenant, relationshipType));

      // ENTERPRISE/REGIONAL_ENTERPRISE tenants carry an enterpriseId (see
      // TenantServiceImpl.onboardTenant) — a user assigned to one of these
      // also gets a direct PartyToPartyRelationship to that enterprise,
      // discriminated by RelationshipTypeEnum.ENTERPRISE. Deduped by
      // enterprise id so multiple tenant assignments under the same
      // enterprise don't produce duplicate relations.
      if (tenant.getEnterpriseId() != null && seenEnterpriseIds.add(tenant.getEnterpriseId())) {
        relations.add(buildEnterpriseRelation(enterpriseQueryClient.getById(tenant.getEnterpriseId())));
      }

      List<String> roleCodes =
          assignment.getRoleIds() != null && !assignment.getRoleIds().isEmpty()
              ? resolveRoles(user.getId(), new ObjectId(tenant.getId()), assignment.getRoleIds())
              : List.of();

      assignmentResponses.add(
          TenantAssignmentResponse.builder()
              .tenantId(tenant.getId())
              .relationshipTypeCode(relationshipType.getCode())
              .externalLoginId(externalLoginId)
              .roleCodes(roleCodes)
              .build());
    }

    user.setPartyRelationships(relations);
    userRepository.save(user);

    return UserResponse.builder()
        .userId(user.getIdAsString())
        .username(user.getUsername())
        .email(user.getEmail())
        .userCategoryCode(userCategory.getCode())
        .tenantAssignments(assignmentResponses)
        .build();
  }

  /**
   * Grants an already-existing user roles across one or more tenants in one
   * call. Never creates a UserEntity, never touches Keycloak (the user
   * already has their one Keycloak identity from original creation — same
   * model as createUser's non-primary assignments). Per tenant: if the user
   * already has a PartyToPartyRelationship to it, that's left as-is (not
   * duplicated); roleIds REPLACE that tenant's UserRole roles entirely (see
   * resolveRoles) — the frontend sends the user's whole desired role set
   * each time, not just new additions, so this is a sync, not a merge.
   * Also mirrors createUser's ENTERPRISE-relation rule: if the tenant has an
   * enterpriseId (ENTERPRISE/REGIONAL_ENTERPRISE), the user gets a direct
   * PartyToPartyRelationship to that enterprise too, deduped against their
   * existing relations.
   */
  @Override
  public AssignTenantRolesResponse assignTenantRoles(String userId, AssignTenantRolesRequest request) {
    UserEntity user =
        userRepository
            .findById(new ObjectId(userId))
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

    List<PartyToPartyRelationship> relations = new ArrayList<>(user.getPartyRelationships());
    List<TenantRoleAssignmentResult> results = new ArrayList<>();

    for (TenantRoleAssignmentRequest assignment : request.getTenantAssignments()) {
      TenantResponse tenant = tenantQueryClient.getById(assignment.getTenantId());

      RelationshipTypeEnum relationshipType =
          assignment.getRelationshipTypeCode() != null ? assignment.getRelationshipTypeCode() : RelationshipTypeEnum.TENANT;

      boolean alreadyAssigned =
          relations.stream().anyMatch(relation -> tenant.getId().equals(relation.getToPartyId().toHexString()));
      if (!alreadyAssigned) {
        relations.add(buildRelation(tenant, relationshipType));
      }

      // Same ENTERPRISE-relation rule as createUser: a tenant with an
      // enterpriseId (ENTERPRISE/REGIONAL_ENTERPRISE) also grants the user a
      // direct PartyToPartyRelationship to that enterprise. Checked against
      // the user's full relation list (not just this call's additions) so a
      // user already linked to the enterprise from an earlier call/tenant
      // doesn't get a duplicate.
      if (tenant.getEnterpriseId() != null) {
        boolean alreadyLinkedToEnterprise =
            relations.stream()
                .anyMatch(relation -> tenant.getEnterpriseId().equals(relation.getToPartyId().toHexString()));
        if (!alreadyLinkedToEnterprise) {
          relations.add(buildEnterpriseRelation(enterpriseQueryClient.getById(tenant.getEnterpriseId())));
        }
      }

      List<String> roleCodes = resolveRoles(user.getId(), new ObjectId(tenant.getId()), assignment.getRoleIds());
      results.add(
          TenantRoleAssignmentResult.builder()
              .tenantId(tenant.getId())
              .relationshipTypeCode(relationshipType.getCode())
              .roleCodes(roleCodes)
              .build());
    }

    user.setPartyRelationships(relations);
    userRepository.save(user);

    return AssignTenantRolesResponse.builder().userId(userId).assignments(results).build();
  }

  /**
   * REPLACES the (userId, tenantId) UserRole's roles with exactly what
   * roleIds resolves to — not an additive merge. The frontend sends the
   * user's whole desired role set for this tenant every time (already-held
   * roles and new ones together), so additive merging would make a role
   * impossible to ever remove via this endpoint. Deduped by the fetched
   * Role's canonical id string, so a repeated roleId in the request
   * collapses to one entry rather than producing a duplicate.
   */
  private List<String> resolveRoles(ObjectId userId, ObjectId tenantId, List<String> roleIds) {
    UserRole userRole =
        userRoleRepository
            .findByUserIdAndTenantId(userId, tenantId)
            .orElseGet(() -> UserRole.builder().userId(userId).tenantId(tenantId).build());

    Map<String, UserRole.EmbeddedRole> rolesById = new LinkedHashMap<>();
    for (String roleId : roleIds) {
      Role role =
          roleRepository
              .findById(new ObjectId(roleId))
              .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
      rolesById.put(
          role.getId().toHexString(),
          UserRole.EmbeddedRole.builder()
              .id(role.getId())
              .code(role.getCode())
              .name(role.getName())
              .roleType(role.getRoleType())
              .build());
    }
    userRole.setRoles(new ArrayList<>(rolesById.values()));
    userRoleRepository.save(userRole);

    return userRole.getRoles().stream().map(UserRole.EmbeddedRole::getCode).toList();
  }

  private PartyToPartyRelationship buildRelation(TenantResponse tenant, RelationshipTypeEnum relationshipType) {
    return PartyToPartyRelationship.builder()
        .toPartyId(new ObjectId(tenant.getId()))
        .relationshipTypeCode(relationshipType.getCode())
        .relationshipReferenceName(tenant.getTenantName())
        .build();
  }

  private PartyToPartyRelationship buildEnterpriseRelation(EnterpriseResponse enterprise) {
    return PartyToPartyRelationship.builder()
        .toPartyId(new ObjectId(enterprise.getEnterpriseId()))
        .relationshipTypeCode(RelationshipTypeEnum.ENTERPRISE.getCode())
        .relationshipTypeDecription(RelationshipTypeEnum.ENTERPRISE.getDescription())
        .relationshipReferenceName(enterprise.getEnterpriseCompanyName())
        .build();
  }

  private UserRepresentation buildKeycloakUser(UserEntity user, String tenantId) {
    UserRepresentation representation = new UserRepresentation();
    representation.setUsername(user.getUsername());
    representation.setEmail(user.getEmail());
    representation.setEmailVerified(false);
    representation.setFirstName(user.getFirstName());
    representation.setLastName(user.getLastName());
    representation.setEnabled(true);
    representation.setRequiredActions(List.of(REQUIRED_ACTION_VERIFY_EMAIL, REQUIRED_ACTION_UPDATE_PASSWORD));

    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("userId", List.of(user.getIdAsString()));
    attributes.put("tenantId", List.of(tenantId));
    representation.setAttributes(attributes);

    return representation;
  }
}
