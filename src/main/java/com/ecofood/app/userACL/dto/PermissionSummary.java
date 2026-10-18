package com.ecofood.app.userACL.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** A permission as embedded on a RolePermission — id/code/name only, no description/status. */
@Data
@AllArgsConstructor
@Builder
public class PermissionSummary {
  private String id;
  private String code;
  private String name;
}
