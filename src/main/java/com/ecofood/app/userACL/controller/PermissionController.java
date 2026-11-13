package com.ecofood.app.userACL.controller;

import com.ecofood.app.userACL.dto.PermissionCreateRequest;
import com.ecofood.app.userACL.dto.PermissionResponse;
import com.ecofood.app.userACL.dto.PermissionSummary;
import com.ecofood.app.userACL.dto.PermissionUpdateRequest;
import com.ecofood.app.userACL.dto.PermissionWithRoleRequest;
import com.ecofood.app.userACL.dto.PermissionWithRoleResponse;
import com.ecofood.app.userACL.service.PermissionService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Combined Permission(s) + existing-Role linking (RolePermission) in one
 * call — for easy test setup. roleId must already exist (e.g. created via
 * POST /api/v1/groups). Separate from UserController/TenantController.
 */
@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
public class PermissionController {

  private final PermissionService permissionService;

  @PostMapping
  public ResponseEntity<PermissionWithRoleResponse> createPermission(
      @Valid @RequestBody PermissionWithRoleRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(permissionService.createPermissionsWithRole(request));
  }

  /** Creates a standalone Permission, unattached to any Role — backs the "+ New Permission" form. */
  @PostMapping("/standalone")
  public ResponseEntity<PermissionResponse> createStandalonePermission(
      @Valid @RequestBody PermissionCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(permissionService.createStandalonePermission(request));
  }

  /** Full Permission catalog (id/code/name) — backs the Permissions directory tab under Users. */
  @GetMapping("/directory")
  public ResponseEntity<List<PermissionSummary>> listDirectory() {
    return ResponseEntity.ok(permissionService.listDirectory());
  }

  /** Permission detail — backs the Edit Permission modal's prefill. */
  @GetMapping("/{permissionId}")
  public ResponseEntity<PermissionResponse> getPermission(@PathVariable String permissionId) {
    return ResponseEntity.ok(permissionService.getById(permissionId));
  }

  /** Updates a permission's basic fields — backs the Edit Permission modal's Save action. */
  @PutMapping("/{permissionId}")
  public ResponseEntity<PermissionResponse> updatePermission(
      @PathVariable String permissionId, @Valid @RequestBody PermissionUpdateRequest request) {
    return ResponseEntity.ok(permissionService.updatePermission(permissionId, request));
  }
}
