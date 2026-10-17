package com.ecofood.app.common.dto;

import com.ecofood.app.userACL.entity.RelationshipTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

/**
 * One tenant+role grant for an ALREADY-EXISTING user — see
 * AssignTenantRolesRequest, which carries a list of these so one call can
 * grant roles across multiple tenants at once (mirrors
 * UserCreateRequest/TenantAssignmentRequest's multi-tenant shape).
 */
@Data
public class TenantRoleAssignmentRequest {

  @NotBlank
  private String tenantId;

  private RelationshipTypeEnum relationshipTypeCode;

  @NotEmpty
  private List<String> roleIds;
}
