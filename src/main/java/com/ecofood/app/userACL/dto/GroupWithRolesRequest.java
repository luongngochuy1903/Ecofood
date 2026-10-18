package com.ecofood.app.userACL.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

/**
 * POST /api/v1/groups — creates a Group, one or more new Roles, and links
 * them all into one GroupRole in a single call, for easy test setup (no
 * longer three separate calls). roles are always NEW rows here — this
 * endpoint doesn't accept existing role ids; see
 * PermissionWithRoleRequest for the "role already exists" counterpart.
 */
@Data
public class GroupWithRolesRequest {

  @NotNull
  @Valid
  private GroupCreateRequest group;

  @NotEmpty
  @Valid
  private List<RoleCreateRequest> roles;
}
