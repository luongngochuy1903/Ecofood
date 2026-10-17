package com.ecofood.app.userACL.dto;

import com.ecofood.app.userACL.entity.RoleType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class RoleResponse {
  private String id;
  private String name;
  private String code;
  private RoleType roleType;
  private String statusCode;
  private String statusDescription;
}
