package com.ecofood.app.userACL.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** id/code/name only — backs the Group picker shown on the Create Role form. */
@Data
@AllArgsConstructor
@Builder
public class GroupSummary {
  private String id;
  private String code;
  private String name;
}
