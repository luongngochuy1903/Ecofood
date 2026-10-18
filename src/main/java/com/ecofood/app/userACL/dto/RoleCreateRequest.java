package com.ecofood.app.userACL.dto;

import com.ecofood.app.userACL.entity.RoleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** POST /api/v1/roles — creates a Role row. Role codes are plain data, managed by a human, not an enum. */
@Data
public class RoleCreateRequest {

  @NotBlank
  private String name;

  @NotBlank
  private String code;

  @NotNull
  private RoleType roleType;

  private String statusCode;

  private String statusDescription;
}
