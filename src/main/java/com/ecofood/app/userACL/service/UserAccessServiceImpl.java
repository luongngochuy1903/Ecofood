package com.ecofood.app.userACL.service;

import com.ecofood.core.service.UserAccessService;
import com.ecofood.app.userACL.entity.GroupRole;
import com.ecofood.app.userACL.entity.RolePermission;
import com.ecofood.app.userACL.entity.UserRole;
import com.ecofood.app.userACL.repository.GroupRoleRepository;
import com.ecofood.app.userACL.repository.RolePermissionRepository;
import com.ecofood.app.userACL.repository.UserRoleRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Resolves a user's actual role/permission/roleGroups codes for one tenant,
 * so InternalTokenService (auth module) never has to import userACL's
 * repositories directly — same module-boundary rule as
 * TenantQueryServiceImpl/UserQueryServiceImpl.
 *
 * A user with no UserRole for the tenant gets empty roles/permissions/
 * roleGroups (e.g. a non-primary tenant assignment from
 * UserServiceImpl.createUser, which never calls
 * UserRoleAssignmentServiceImpl) rather than an error — minting a token for
 * someone with no assigned role yet is valid, just low-privilege.
 *
 * roleGroups matches DCMP's real computation exactly (confirmed by reading
 * Service_SecurityManagement's UserRoleUtils.createTenantRoleResult
 * directly): group membership is NEVER a direct user-to-group link — scan
 * every GroupRole, keep the ones whose roles list overlaps this user's
 * UserRole role ids, and emit each match's (denormalized) name. Whoever
 * seeds Group/GroupRole rows is responsible for setting name to the exact
 * level strings ACLService checks against (e.g. "MASTER_TENANT") — that's a
 * data convention, not something this code translates/maps.
 */
@Service
@RequiredArgsConstructor
public class UserAccessServiceImpl implements UserAccessService {

  private final UserRoleRepository userRoleRepository;
  private final RolePermissionRepository rolePermissionRepository;
  private final GroupRoleRepository groupRoleRepository;

  @Override
  public UserAccessClaims resolveAccess(ObjectId userId, ObjectId tenantId) {
    return userRoleRepository
        .findByUserIdAndTenantId(userId, tenantId)
        .map(this::toAccessClaims)
        .orElseGet(() -> new UserAccessClaims(List.of(), List.of(), List.of()));
  }

  private UserAccessClaims toAccessClaims(UserRole userRole) {
    List<UserRole.EmbeddedRole> roles = userRole.getRoles();

    List<String> roleCodes = roles.stream().map(UserRole.EmbeddedRole::getCode).distinct().toList();

    List<ObjectId> roleIds = roles.stream().map(UserRole.EmbeddedRole::getId).distinct().toList();

    List<String> permissionCodes =
        roleIds.stream()
            .flatMap(roleId -> rolePermissionRepository.findByRoleId(roleId).stream())
            .flatMap(rolePermission -> rolePermission.getPermissions().stream())
            .map(RolePermission.EmbeddedPermission::getCode)
            .distinct()
            .toList();

    Set<ObjectId> roleIdSet = Set.copyOf(roleIds);
    List<String> roleGroups =
        groupRoleRepository.findAll().stream()
            .filter(groupRole -> groupRole.getRoles().stream().anyMatch(roleIdSet::contains))
            .map(GroupRole::getName)
            .distinct()
            .collect(Collectors.toList());

    return new UserAccessClaims(roleCodes, permissionCodes, roleGroups);
  }
}
