package com.ecofood.app.userACL.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** PUT /api/v1/permissions/{permissionId} — updates a Permission catalog row's basic fields. */
@Data
public class PermissionUpdateRequest {

  @NotBlank
  private String name;

  @NotBlank
  private String code;

  private String description;

  private String statusCode;

  private String statusDescription;
}
