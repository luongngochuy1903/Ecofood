package com.ecofood.app.userACL.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Links Role ids into a group's GroupRole doc — see GroupService.addRolesToGroup. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddRolesToGroupRequest {

  @NotEmpty
  private List<String> roleIds;
}
