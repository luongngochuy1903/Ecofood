package com.ecofood.app.userACL.controller;

import com.ecofood.core.dto.AssignTenantRolesRequest;
import com.ecofood.core.dto.AssignTenantRolesResponse;
import com.ecofood.core.dto.EligibleTenantResponse;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.UserCreateRequest;
import com.ecofood.core.dto.UserDetailResponse;
import com.ecofood.core.dto.UserDirectoryResponse;
import com.ecofood.core.dto.UserResponse;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Creates a user under an already-existing tenant, and grants existing users roles on (new or existing) tenants. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;

  @PostMapping
  public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(request));
  }

  /** Basic-info section of the "Edit User" modal — see UserDetailResponse. */
  @GetMapping("/{userId}")
  public ResponseEntity<UserDetailResponse> getById(@PathVariable String userId) {
    return ResponseEntity.ok(userService.getById(userId));
  }

  @PostMapping("/{userId}/tenant-assignments")
  public ResponseEntity<AssignTenantRolesResponse> assignTenantRoles(
      @PathVariable String userId, @Valid @RequestBody AssignTenantRolesRequest request) {
    return ResponseEntity.ok(userService.assignTenantRoles(userId, request));
  }

  /** Resolves the caller from the internal access token's subject claim. */
  @GetMapping("/eligible-tenants")
  public ResponseEntity<List<EligibleTenantResponse>> getEligibleTenants() {
    String userId = AuthenticationContext.getUserId().toHexString();
    return ResponseEntity.ok(userService.getEligibleTenants(userId));
  }

  /**
   * Backs the "Users" sub-tab — paginated, searchable, role-scoped listing
   * (see UserService.listUserDirectory). Authenticated-only: carved out of
   * the /api/v1/users/** permitAll in SecurityConfig.
   */
  @GetMapping("/directory")
  public ResponseEntity<PageResponse<UserDirectoryResponse>> getUserDirectory(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "updatedAt,desc") String sort,
      @RequestParam(required = false) String search) {
    return ResponseEntity.ok(userService.listUserDirectory(page, size, sort, search));
  }
}
