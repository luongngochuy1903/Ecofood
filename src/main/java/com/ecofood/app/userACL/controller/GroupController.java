package com.ecofood.app.userACL.controller;

import com.ecofood.app.userACL.dto.GroupWithRolesRequest;
import com.ecofood.app.userACL.dto.GroupWithRolesResponse;
import com.ecofood.app.userACL.service.GroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Combined Group + Role(s) + GroupRole creation in one call — for easy test
 * setup. Separate from PermissionController/UserController. No standalone
 * "create a bare group" or "link existing roles" endpoint anymore; see
 * GroupService for those as internal helpers.
 */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class GroupController {

  private final GroupService groupService;

  @PostMapping
  public ResponseEntity<GroupWithRolesResponse> createGroup(@Valid @RequestBody GroupWithRolesRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(groupService.createGroupWithRoles(request));
  }
}
