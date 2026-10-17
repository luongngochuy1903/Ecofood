package com.ecofood.app.common.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** Lives in common as UserService's public response contract, consumed via CoreUserClient. */
@Data
@AllArgsConstructor
@Builder
public class UserResponse {
  private String userId;
  private String username;
  private String email;
  private String userCategoryCode;
  private List<TenantAssignmentResponse> tenantAssignments;
}
