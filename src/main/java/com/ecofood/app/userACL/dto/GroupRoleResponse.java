package com.ecofood.app.userACL.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class GroupRoleResponse {
  private String groupId;
  private String name;
  private String code;
  private List<RoleSummary> roles;
}
