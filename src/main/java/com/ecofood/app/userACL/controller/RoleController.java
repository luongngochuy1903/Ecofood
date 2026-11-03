package com.ecofood.app.userACL.controller;

import com.ecofood.app.userACL.dto.AddPermissionsToRoleRequest;
import com.ecofood.app.userACL.dto.AddRolesToGroupRequest;
import com.ecofood.app.userACL.dto.RoleCreateRequest;
import com.ecofood.app.userACL.dto.RoleDetailResponse;
import com.ecofood.app.userACL.dto.RolePermissionResponse;
import com.ecofood.app.userACL.dto.RoleResponse;
import com.ecofood.app.userACL.dto.RoleSummary;
import com.ecofood.app.userACL.dto.RoleUpdateRequest;
import com.ecofood.app.userACL.service.GroupService;
import com.ecofood.app.userACL.service.RolePermissionService;
import com.ecofood.app.userACL.service.RoleService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

  private final RoleService roleService;
  private final RolePermissionService rolePermissionService;
  private final GroupService groupService;

  /**
   * Backs the Access & Permissions role picker — ?actor=ENTERPRISE returns
   * only ENTERPRISE_* roles, so on for ECO/MASTER/TENANT (see
   * RoleService.listByActor).
   */
  @GetMapping
  public ResponseEntity<List<RoleResponse>> listByActor(@RequestParam String actor) {
    return ResponseEntity.ok(roleService.listByActor(actor));
  }

  /** Full Role catalog (id/code/name) — backs the Roles directory tab under Users. */
  @GetMapping("/directory")
  public ResponseEntity<List<RoleSummary>> listDirectory() {
    return ResponseEntity.ok(roleService.listDirectory());
  }

  /**
   * Creates a Role — backs the Roles tab's Create Role form. When groupId is
   * set, the new role is also linked into that existing Group's GroupRole
   * doc in the same call (see GroupService.addRolesToGroup).
   */
  @PostMapping
  public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody RoleCreateRequest request) {
    RoleResponse role = roleService.createRole(request);
    if (request.getGroupId() != null && !request.getGroupId().isBlank()) {
      groupService.addRolesToGroup(
          request.getGroupId(), AddRolesToGroupRequest.builder().roleIds(List.of(role.getId())).build());
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(role);
  }

  /** Role detail plus its current group (if any) — backs the Edit Role modal's prefill. */
  @GetMapping("/{roleId}")
  public ResponseEntity<RoleDetailResponse> getRole(@PathVariable String roleId) {
    return ResponseEntity.ok(roleService.getById(roleId));
  }

  /** Updates a role's basic fields and group membership — backs the Edit Role modal's Save action. */
  @PutMapping("/{roleId}")
  public ResponseEntity<RoleResponse> updateRole(@PathVariable String roleId, @Valid @RequestBody RoleUpdateRequest request) {
    return ResponseEntity.ok(roleService.updateRole(roleId, request));
  }

  /** Current permissions assigned to a role — backs the Manage Permissions modal's prefill. */
  @GetMapping("/{roleId}/permissions")
  public ResponseEntity<RolePermissionResponse> getPermissions(@PathVariable String roleId) {
    return ResponseEntity.ok(rolePermissionService.getPermissionsForRole(roleId));
  }

  /** Adds (never replaces) permissions on a role — backs the Manage Permissions modal's Save action. */
  @PostMapping("/{roleId}/permissions")
  public ResponseEntity<RolePermissionResponse> addPermissions(
      @PathVariable String roleId, @Valid @RequestBody AddPermissionsToRoleRequest request) {
    return ResponseEntity.ok(rolePermissionService.addPermissionsToRole(roleId, request));
  }
}
