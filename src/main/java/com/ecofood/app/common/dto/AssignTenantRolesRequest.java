package com.ecofood.app.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

/**
 * POST /api/v1/users/{userId}/tenant-assignments — grants an ALREADY-
 * EXISTING user roles across one or more tenants (new or already-assigned)
 * in a single call, mirroring UserCreateRequest's multi-tenant shape. Unlike
 * onboarding's UserRoleAssignmentServiceImpl (which derives the role from
 * partnerType), the caller picks the role(s) explicitly per tenant here —
 * this is a deliberate admin action, not an onboarding default.
 *
 * For each entry: if the user already has a PartyToPartyRelationship to
 * that tenantId, it's left as-is (not duplicated); roleIds REPLACE that
 * tenant's UserRole roles entirely — the frontend sends the user's whole
 * desired role set for that tenant each call (already-held roles and new
 * ones together), not just an incremental addition, so this is a sync, not
 * a merge.
 */
@Data
public class AssignTenantRolesRequest {

  @NotEmpty
  @Valid
  private List<TenantRoleAssignmentRequest> tenantAssignments;
}
