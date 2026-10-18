package com.ecofood.app.userACL.service;

import com.ecofood.app.userACL.dto.AddPermissionsToRoleRequest;
import com.ecofood.app.userACL.dto.PermissionCreateRequest;
import com.ecofood.app.userACL.dto.PermissionResponse;
import com.ecofood.app.userACL.dto.PermissionWithRoleRequest;
import com.ecofood.app.userACL.dto.PermissionWithRoleResponse;
import com.ecofood.app.userACL.dto.RolePermissionResponse;
import com.ecofood.app.userACL.entity.Permission;
import com.ecofood.app.userACL.repository.PermissionRepository;
import com.ecofood.app.userACL.repository.RoleRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Owns Permission catalog creation — POST /api/v1/permissions creates one or
 * more Permissions and links them into an ALREADY-EXISTING Role's
 * RolePermission doc in one call, for easy test setup.
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

  private final PermissionRepository permissionRepository;
  private final RoleRepository roleRepository;
  private final RolePermissionService rolePermissionService;

  /**
   * Validates the role exists BEFORE creating any permission, so a bad
   * roleId never leaves orphaned Permission rows behind. Creates every
   * permission in the request as a brand-new row, then links them all into
   * the existing role's RolePermission doc via addPermissionsToRole.
   */
  public PermissionWithRoleResponse createPermissionsWithRole(PermissionWithRoleRequest request) {
    if (!roleRepository.existsById(new ObjectId(request.getRoleId()))) {
      throw new IllegalArgumentException("Role not found: " + request.getRoleId());
    }

    List<PermissionResponse> permissions = new ArrayList<>();
    List<String> permissionIds = new ArrayList<>();
    for (PermissionCreateRequest permissionRequest : request.getPermissions()) {
      PermissionResponse permission = createPermission(permissionRequest);
      permissions.add(permission);
      permissionIds.add(permission.getId());
    }

    RolePermissionResponse rolePermission =
        rolePermissionService.addPermissionsToRole(
            request.getRoleId(), AddPermissionsToRoleRequest.builder().permissionIds(permissionIds).build());

    return PermissionWithRoleResponse.builder().permissions(permissions).rolePermission(rolePermission).build();
  }

  private PermissionResponse createPermission(PermissionCreateRequest request) {
    Permission.PermissionStatus status =
        request.getStatusCode() != null || request.getStatusDescription() != null
            ? Permission.PermissionStatus.builder()
                .statusCode(request.getStatusCode())
                .statusDescription(request.getStatusDescription())
                .build()
            : null;

    Permission permission =
        Permission.builder()
            .name(request.getName())
            .code(request.getCode())
            .description(request.getDescription())
            .permissionStatus(status)
            .build();
    permissionRepository.save(permission);
    return toResponse(permission);
  }

  private PermissionResponse toResponse(Permission permission) {
    Permission.PermissionStatus status = permission.getPermissionStatus();
    return PermissionResponse.builder()
        .id(permission.getIdAsString())
        .name(permission.getName())
        .code(permission.getCode())
        .description(permission.getDescription())
        .statusCode(status != null ? status.getStatusCode() : null)
        .statusDescription(status != null ? status.getStatusDescription() : null)
        .build();
  }
}
