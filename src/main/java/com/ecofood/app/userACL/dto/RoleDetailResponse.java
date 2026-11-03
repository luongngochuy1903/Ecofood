package com.ecofood.app.userACL.dto;

import com.ecofood.app.userACL.entity.RoleType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** GET /api/v1/roles/{roleId} — backs the Edit Role modal's prefill, including its current group (if any). */
@Data
@AllArgsConstructor
@Builder
public class RoleDetailResponse {
  private String id;
  private String name;
  private String code;
  private RoleType roleType;
  private String statusCode;
  private String statusDescription;
  private String groupId;
  private String groupName;
}
