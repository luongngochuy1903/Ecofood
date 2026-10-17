package com.ecofood.app.userACL.service;

import com.ecofood.app.userACL.dto.RoleCreateRequest;
import com.ecofood.app.userACL.dto.RoleResponse;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Owns Role catalog creation — POST /api/v1/roles. */
@Service
@RequiredArgsConstructor
public class RoleService {

  private final RoleRepository roleRepository;

  public RoleResponse createRole(RoleCreateRequest request) {
    Role.RoleStatus status =
        request.getStatusCode() != null || request.getStatusDescription() != null
            ? Role.RoleStatus.builder()
                .statusCode(request.getStatusCode())
                .statusDescription(request.getStatusDescription())
                .build()
            : null;

    Role role =
        Role.builder()
            .name(request.getName())
            .code(request.getCode())
            .roleType(request.getRoleType())
            .roleStatus(status)
            .build();
    roleRepository.save(role);
    return toResponse(role);
  }

  private RoleResponse toResponse(Role role) {
    Role.RoleStatus status = role.getRoleStatus();
    return RoleResponse.builder()
        .id(role.getIdAsString())
        .name(role.getName())
        .code(role.getCode())
        .roleType(role.getRoleType())
        .statusCode(status != null ? status.getStatusCode() : null)
        .statusDescription(status != null ? status.getStatusDescription() : null)
        .build();
  }
}
