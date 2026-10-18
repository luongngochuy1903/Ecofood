package com.ecofood.app.userACL.controller;

import com.ecofood.app.userACL.dto.PermissionWithRoleRequest;
import com.ecofood.app.userACL.dto.PermissionWithRoleResponse;
import com.ecofood.app.userACL.service.PermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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
}
