package com.ecofood.app.userACL.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** permissionIds are existing Permission catalog ids — see RolePermissionService.addPermissionsToRole. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddPermissionsToRoleRequest {

  @NotEmpty
  private List<String> permissionIds;
}
