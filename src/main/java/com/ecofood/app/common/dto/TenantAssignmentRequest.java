package com.ecofood.app.common.dto;

import com.ecofood.app.userACL.entity.RelationshipTypeEnum;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One tenant membership being assigned to the user in this call — embeds
 * what's actually scoped PER TENANT (which tenant, what kind of
 * relationship, which roles). A single UserCreateRequest can carry several
 * of these, since one person can be assigned to multiple tenants (each its
 * own Keycloak realm — see UserServiceImpl for how that's mirrored).
 *
 * userCategoryCode is NOT here — it's a per-USER classification (see
 * UserCreateRequest), not something that varies per tenant assignment.
 *
 * roleIds is OPTIONAL — a caller can create a user+tenant connection with no
 * role yet, or grant role(s) in the same call (resolved via
 * UserServiceImpl.resolveRoles, same helper AssignTenantRolesRequest uses).
 *
 * Lives in common (not userACL) because it's part of UserService's public
 * interface contract, which other modules depend on via CoreUserClient.
 * relationshipTypeCode stays typed as userACL.entity.RelationshipTypeEnum —
 * moving every entity/enum into common wasn't part of this refactor's scope.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantAssignmentRequest {

  @NotBlank
  private String tenantId;

  private RelationshipTypeEnum relationshipTypeCode;

  private List<String> roleIds;
}
