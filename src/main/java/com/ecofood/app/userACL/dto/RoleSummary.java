package com.ecofood.app.userACL.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** A role as linked on a GroupRole — id/code/name only. GroupRole.roles itself only stores raw ids, so this is resolved at read time. */
@Data
@AllArgsConstructor
@Builder
public class RoleSummary {
  private String id;
  private String code;
  private String name;
}
