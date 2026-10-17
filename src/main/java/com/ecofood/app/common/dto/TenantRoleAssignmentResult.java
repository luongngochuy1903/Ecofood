package com.ecofood.app.common.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** Result of one tenant+role grant — see AssignTenantRolesResponse, which carries a list of these. */
@Data
@AllArgsConstructor
@Builder
public class TenantRoleAssignmentResult {
  private String tenantId;
  private String relationshipTypeCode;
  private List<String> roleCodes;
}
