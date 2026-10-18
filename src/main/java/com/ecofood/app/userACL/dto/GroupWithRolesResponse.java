package com.ecofood.app.userACL.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class GroupWithRolesResponse {
  private GroupResponse group;
  private GroupRoleResponse groupRole;
}
