package com.ecofood.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /api/v1/auth/switch-tenant — the tenant to re-mint the internal token pair for. */
@Data
public class SwitchTenantRequest {

  @NotBlank
  private String tenantId;
}
