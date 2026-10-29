package com.ecofood.app.userACL.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.userACL.dto.RoleCreateRequest;
import com.ecofood.app.userACL.dto.RoleResponse;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.repository.RoleRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Owns Role catalog creation — POST /api/v1/roles — and the actor-scoped role listing the Access & Permissions role picker calls. */
@Service
@RequiredArgsConstructor
public class RoleService {

  /** The 4 Role Actors a role code can be prefixed with — see app/docs/ACL_ROLE_MODEL.md §1.1. */
  private static final Set<String> ROLE_ACTORS = Set.of("ECO", "ENTERPRISE", "MASTER", "TENANT");

  private final RoleRepository roleRepository;

  /**
   * Every role whose code is prefixed by the given actor (e.g. actor
   * "ENTERPRISE" -> every "ENTERPRISE_*" role) — backs the Access &
   * Permissions role picker, which depends on the actor derived from
   * whichever tenant the frontend has selected for that assignment row.
   */
  public List<RoleResponse> listByActor(String actor) {
    if (!ROLE_ACTORS.contains(actor)) {
      throw new BadRequestException("Unsupported role actor: " + actor);
    }
    return roleRepository.findByCodeStartingWith(actor + "_").stream().map(this::toResponse).toList();
  }

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
