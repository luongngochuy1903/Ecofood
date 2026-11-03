package com.ecofood.app.userACL.dto;

import com.ecofood.app.userACL.entity.RoleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * PUT /api/v1/roles/{roleId} — updates a Role row's basic fields. groupId
 * reassigns the role's group membership: blank/omitted means "no group"
 * (removes the role from whatever group it was in), set means "move the
 * role into this group" — see RoleService.updateRole.
 */
@Data
public class RoleUpdateRequest {

  @NotBlank
  private String name;

  @NotBlank
  private String code;

  @NotNull
  private RoleType roleType;

  private String statusCode;

  private String statusDescription;

  private String groupId;
}
