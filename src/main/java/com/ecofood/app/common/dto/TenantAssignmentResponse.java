package com.ecofood.app.common.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * One tenant membership's result. externalLoginId is only non-null for the
 * FIRST assignment — a user gets exactly one Keycloak identity, mirrored
 * into that primary tenant's realm only (matches DCMP's real model); every
 * other assignment is a pure authorization record with no Keycloak login of
 * its own. roleCodes is empty if the request's matching TenantAssignmentRequest
 * didn't specify roleIds — assigning a role on creation is optional.
 */
@Data
@AllArgsConstructor
@Builder
public class TenantAssignmentResponse {
  private String tenantId;
  private String relationshipTypeCode;
  private String externalLoginId;
  private List<String> roleCodes;
}
