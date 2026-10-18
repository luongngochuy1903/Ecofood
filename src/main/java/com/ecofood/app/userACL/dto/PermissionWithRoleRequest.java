package com.ecofood.app.userACL.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

/**
 * POST /api/v1/permissions — creates one or more new Permissions and links
 * them into an ALREADY-EXISTING Role's RolePermission doc in a single call.
 * roleId must reference a Role created beforehand (e.g. via
 * GroupWithRolesRequest) — this endpoint never creates a Role itself.
 */
@Data
public class PermissionWithRoleRequest {

  @NotBlank
  private String roleId;

  @NotEmpty
  @Valid
  private List<PermissionCreateRequest> permissions;
}
