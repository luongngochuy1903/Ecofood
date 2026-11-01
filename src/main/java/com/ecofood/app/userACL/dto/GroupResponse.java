package com.ecofood.app.userACL.dto;

import com.ecofood.core.enums.GroupType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class GroupResponse {
  private String id;
  private String name;
  private String code;
  private GroupType groupType;
  private String groupDescription;
  private String statusCode;
  private String statusDescription;
}
