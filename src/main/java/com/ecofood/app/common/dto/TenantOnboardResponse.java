package com.ecofood.app.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** Lives in common as TenantService's public response contract, consumed via CoreTenantClient. */
@Data
@AllArgsConstructor
@Builder
public class TenantOnboardResponse {
  private String tenantId;
  private String tenantName;
  private String realm;
  private String clientId;
  private UserResponse user;
}
