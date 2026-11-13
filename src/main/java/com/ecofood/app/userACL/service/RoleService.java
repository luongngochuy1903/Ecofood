package com.ecofood.app.userACL.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.userACL.dto.RoleCreateRequest;
import com.ecofood.app.userACL.dto.RoleDetailResponse;
import com.ecofood.app.userACL.dto.RoleResponse;
import com.ecofood.app.userACL.dto.RoleSummary;
import com.ecofood.app.userACL.dto.RoleUpdateRequest;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.GroupRole;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.repository.GroupRepository;
import com.ecofood.app.userACL.repository.GroupRoleRepository;
import com.ecofood.app.userACL.repository.RoleRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Owns Role catalog creation — POST /api/v1/roles — and the actor-scoped role listing the Access & Permissions role picker calls. */
@Service
@RequiredArgsConstructor
public class RoleService {

  /** The 4 Role Actors a role code can be prefixed with — see app/docs/ACL_ROLE_MODEL.md §1.1. */
  private static final Set<String> ROLE_ACTORS = Set.of("ECO", "ENTERPRISE", "MASTER", "TENANT");

  private final RoleRepository roleRepository;
  private final GroupRoleRepository groupRoleRepository;
  private final GroupRepository groupRepository;

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

  /** Full Role catalog, alphabetical by name — backs the Roles directory tab under Users. */
  public List<RoleSummary> listDirectory() {
    return roleRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
        .map(
            role ->
                RoleSummary.builder()
                    .id(role.getIdAsString())
                    .code(role.getCode())
                    .name(role.getName())
                    .build())
        .toList();
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

  /** Role detail plus its current group (if any) — backs the Edit Role modal's prefill. */
  public RoleDetailResponse getById(String roleId) {
    ObjectId roleObjectId = new ObjectId(roleId);
    Role role = roleRepository.findById(roleObjectId).orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
    Role.RoleStatus status = role.getRoleStatus();

    Optional<GroupRole> currentGroupRole = groupRoleRepository.findByRolesContaining(roleObjectId).stream().findFirst();

    return RoleDetailResponse.builder()
        .id(role.getIdAsString())
        .name(role.getName())
        .code(role.getCode())
        .roleType(role.getRoleType())
        .statusCode(status != null ? status.getStatusCode() : null)
        .statusDescription(status != null ? status.getStatusDescription() : null)
        .groupId(currentGroupRole.map(gr -> gr.getGroupId().toHexString()).orElse(null))
        .groupName(currentGroupRole.map(GroupRole::getName).orElse(null))
        .build();
  }

  /**
   * Updates a Role's basic fields and reassigns its group membership per
   * request.groupId (blank/null removes it from any group it's currently
   * in; a set value moves it into that group, leaving any other group it
   * was in).
   */
  public RoleResponse updateRole(String roleId, RoleUpdateRequest request) {
    ObjectId roleObjectId = new ObjectId(roleId);
    Role role = roleRepository.findById(roleObjectId).orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));

    Role.RoleStatus status =
        request.getStatusCode() != null || request.getStatusDescription() != null
            ? Role.RoleStatus.builder()
                .statusCode(request.getStatusCode())
                .statusDescription(request.getStatusDescription())
                .build()
            : null;

    role.setName(request.getName());
    role.setCode(request.getCode());
    role.setRoleType(request.getRoleType());
    role.setRoleStatus(status);
    roleRepository.save(role);

    reassignGroup(roleObjectId, request.getGroupId());

    return toResponse(role);
  }

  private void reassignGroup(ObjectId roleObjectId, String targetGroupId) {
    List<GroupRole> currentGroupRoles = groupRoleRepository.findByRolesContaining(roleObjectId);
    boolean alreadyInTarget = false;
    for (GroupRole groupRole : currentGroupRoles) {
      boolean isTarget = targetGroupId != null && !targetGroupId.isBlank() && groupRole.getGroupId().toHexString().equals(targetGroupId);
      if (isTarget) {
        alreadyInTarget = true;
      } else {
        groupRole.getRoles().remove(roleObjectId);
        groupRoleRepository.save(groupRole);
      }
    }

    if (targetGroupId != null && !targetGroupId.isBlank() && !alreadyInTarget) {
      ObjectId groupObjectId = new ObjectId(targetGroupId);
      Group group =
          groupRepository.findById(groupObjectId).orElseThrow(() -> new IllegalArgumentException("Group not found: " + targetGroupId));
      GroupRole groupRole =
          groupRoleRepository.findByGroupId(groupObjectId).stream()
              .findFirst()
              .orElseGet(() -> GroupRole.builder().groupId(group.getId()).name(group.getName()).code(group.getCode()).build());
      groupRole.getRoles().add(roleObjectId);
      groupRoleRepository.save(groupRole);
    }
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
