package com.ecofood.app.userACL.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class RolePermissionResponse {
  private String roleId;
  private List<PermissionSummary> permissions;
}
