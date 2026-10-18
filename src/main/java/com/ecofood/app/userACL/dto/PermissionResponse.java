package com.ecofood.app.userACL.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class PermissionResponse {
  private String id;
  private String name;
  private String code;
  private String description;
  private String statusCode;
  private String statusDescription;
}
