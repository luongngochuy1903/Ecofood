package com.ecofood.app.userACL.dto;

import com.ecofood.core.enums.GroupType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /api/v1/groups — creates a Group catalog row (e.g. "ENTERPRISE", "MASTER_TENANT", "TENANT", "CUSTOMER"). */
@Data
public class GroupCreateRequest {

  @NotBlank
  private String name;

  @NotBlank
  private String code;

  private GroupType groupType;

  private String groupDescription;

  private String statusCode;

  private String statusDescription;
}
