package com.ecofood.app.common.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class AssignTenantRolesResponse {
  private String userId;
  private List<TenantRoleAssignmentResult> assignments;
}
