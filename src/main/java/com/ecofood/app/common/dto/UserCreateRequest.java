package com.ecofood.app.common.dto;

import com.ecofood.app.userACL.entity.UserCategoryEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/v1/users — creates a user and assigns them to one or more
 * tenants in the same call. userCategoryCode is a per-USER classification
 * (a user is fundamentally a tenant user or a customer, not several at
 * once) — it does NOT vary per tenant assignment, unlike tenantId/
 * relationshipTypeCode which do (see TenantAssignmentRequest), since a
 * single person can be assigned to multiple tenants (e.g. an agency admin
 * managing several client tenants), each mirrored into its own Keycloak
 * realm.
 *
 * Lives in common (not userACL) as part of UserService's public interface
 * contract, consumed by other modules via CoreUserClient.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCreateRequest {

  @NotBlank
  private String username;

  @NotBlank
  @Email
  private String email;

  private String firstName;

  private String lastName;

  private UserCategoryEnum userCategoryCode;

  @NotEmpty
  @Valid
  private List<TenantAssignmentRequest> tenantAssignments;
}
