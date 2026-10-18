package com.ecofood.app.userACL.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class PermissionWithRoleResponse {
  private List<PermissionResponse> permissions;
  private RolePermissionResponse rolePermission;
}
