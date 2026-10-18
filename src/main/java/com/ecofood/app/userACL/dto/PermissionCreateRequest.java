package com.ecofood.app.userACL.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /api/v1/permissions — creates a Permission catalog row. */
@Data
public class PermissionCreateRequest {

  @NotBlank
  private String name;

  @NotBlank
  private String code;

  private String description;

  private String statusCode;

  private String statusDescription;
}
