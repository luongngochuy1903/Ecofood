package com.ecofood.app.userACL.service;

import com.ecofood.app.userACL.dto.AddPermissionsToRoleRequest;
import com.ecofood.app.userACL.dto.PermissionSummary;
import com.ecofood.app.userACL.dto.RolePermissionResponse;
import com.ecofood.app.userACL.entity.Permission;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.entity.RolePermission;
import com.ecofood.app.userACL.repository.PermissionRepository;
import com.ecofood.app.userACL.repository.RolePermissionRepository;
import com.ecofood.app.userACL.repository.RoleRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Owns the Role-to-Permission junction — POST /api/v1/roles/{roleId}/permissions.
 * Appends to (never replaces) a role's existing RolePermission doc, deduping
 * by permission id, so repeated calls are idempotent.
 */
@Service
@RequiredArgsConstructor
public class RolePermissionService {

  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;
  private final RolePermissionRepository rolePermissionRepository;

  public RolePermissionResponse addPermissionsToRole(String roleId, AddPermissionsToRoleRequest request) {
    ObjectId roleObjectId = new ObjectId(roleId);
    Role role =
        roleRepository.findById(roleObjectId).orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));

    RolePermission rolePermission =
        rolePermissionRepository
            .findByRoleId(roleObjectId)
            .orElseGet(() -> RolePermission.builder().roleId(role.getId()).build());

    Map<String, RolePermission.EmbeddedPermission> byId = new LinkedHashMap<>();
    for (RolePermission.EmbeddedPermission existing : rolePermission.getPermissions()) {
      byId.put(existing.getId().toHexString(), existing);
    }

    for (String permissionId : request.getPermissionIds()) {
      ObjectId permissionObjectId = new ObjectId(permissionId);
      Permission permission =
          permissionRepository
              .findById(permissionObjectId)
              .orElseThrow(() -> new IllegalArgumentException("Permission not found: " + permissionId));
      // Key by the fetched entity's canonical id string, not the raw request
      // string — same fix as UserServiceImpl.mergeRoles: a differently-cased
      // hex string for the same permission would otherwise collide with
      // nothing in the map and produce a duplicate entry.
      byId.put(
          permission.getId().toHexString(),
          RolePermission.EmbeddedPermission.builder()
              .id(permission.getId())
              .code(permission.getCode())
              .name(permission.getName())
              .build());
    }

    rolePermission.setPermissions(new ArrayList<>(byId.values()));
    rolePermissionRepository.save(rolePermission);

    List<PermissionSummary> summaries =
        rolePermission.getPermissions().stream()
            .map(
                p ->
                    PermissionSummary.builder().id(p.getId().toHexString()).code(p.getCode()).name(p.getName()).build())
            .toList();

    return RolePermissionResponse.builder().roleId(roleId).permissions(summaries).build();
  }
}
