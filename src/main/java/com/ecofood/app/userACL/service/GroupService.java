package com.ecofood.app.userACL.service;

import com.ecofood.app.userACL.dto.AddRolesToGroupRequest;
import com.ecofood.app.userACL.dto.GroupCreateRequest;
import com.ecofood.app.userACL.dto.GroupResponse;
import com.ecofood.app.userACL.dto.GroupRoleResponse;
import com.ecofood.app.userACL.dto.GroupWithRolesRequest;
import com.ecofood.app.userACL.dto.GroupWithRolesResponse;
import com.ecofood.app.userACL.dto.RoleCreateRequest;
import com.ecofood.app.userACL.dto.RoleResponse;
import com.ecofood.app.userACL.dto.RoleSummary;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.GroupRole;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.repository.GroupRepository;
import com.ecofood.app.userACL.repository.GroupRoleRepository;
import com.ecofood.app.userACL.repository.RoleRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Owns Group catalog creation and the Group-to-Role (GroupRole) junction —
 * POST /api/v1/groups creates a Group, its Roles, and their GroupRole link
 * in one call, for easy test setup. Whoever seeds a Group here is
 * responsible for setting `name` to the exact level string ACLService checks
 * against (e.g. "MASTER_TENANT") — that's a data convention this service
 * does not enforce or translate.
 */
@Service
@RequiredArgsConstructor
public class GroupService {

  private final GroupRepository groupRepository;
  private final GroupRoleRepository groupRoleRepository;
  private final RoleRepository roleRepository;
  private final RoleService roleService;

  /**
   * Creates a Group, creates every Role in the request as a brand-new row,
   * and links them all into one GroupRole — the combined endpoint behind
   * POST /api/v1/groups. Roles here are always newly created; linking an
   * EXISTING role into a group isn't exposed anymore (addRolesToGroup stays
   * as an internal helper, reused here).
   */
  public GroupWithRolesResponse createGroupWithRoles(GroupWithRolesRequest request) {
    GroupResponse group = createGroup(request.getGroup());

    List<String> roleIds = new ArrayList<>();
    for (RoleCreateRequest roleRequest : request.getRoles()) {
      RoleResponse role = roleService.createRole(roleRequest);
      roleIds.add(role.getId());
    }

    GroupRoleResponse groupRole = addRolesToGroup(group.getId(), AddRolesToGroupRequest.builder().roleIds(roleIds).build());

    return GroupWithRolesResponse.builder().group(group).groupRole(groupRole).build();
  }

  private GroupResponse createGroup(GroupCreateRequest request) {
    Group.GroupStatus status =
        request.getStatusCode() != null || request.getStatusDescription() != null
            ? Group.GroupStatus.builder()
                .statusCode(request.getStatusCode())
                .statusDescription(request.getStatusDescription())
                .build()
            : null;

    Group group =
        Group.builder()
            .name(request.getName())
            .code(request.getCode())
            .groupType(request.getGroupType())
            .groupDescription(request.getGroupDescription())
            .status(status)
            .build();
    groupRepository.save(group);
    return toResponse(group);
  }

  /**
   * Links roleIds into this group's GroupRole doc — additive (never
   * replaces), deduping by canonical role id, same pattern as
   * RolePermissionService.addPermissionsToRole. Reuses the FIRST existing
   * GroupRole for this group if one exists (DCMP's real schema allows
   * several per group, but one is sufficient here since the only consumer,
   * UserAccessServiceImpl, already dedupes by name across however many it
   * finds).
   */
  public GroupRoleResponse addRolesToGroup(String groupId, AddRolesToGroupRequest request) {
    ObjectId groupObjectId = new ObjectId(groupId);
    Group group =
        groupRepository.findById(groupObjectId).orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));

    GroupRole groupRole =
        groupRoleRepository.findByGroupId(groupObjectId).stream()
            .findFirst()
            .orElseGet(
                () ->
                    GroupRole.builder()
                        .groupId(group.getId())
                        .name(group.getName())
                        .code(group.getCode())
                        .build());

    Map<String, ObjectId> roleIdsById = new LinkedHashMap<>();
    for (ObjectId existing : groupRole.getRoles()) {
      roleIdsById.put(existing.toHexString(), existing);
    }
    for (String roleId : request.getRoleIds()) {
      Role role =
          roleRepository.findById(new ObjectId(roleId)).orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
      roleIdsById.put(role.getId().toHexString(), role.getId());
    }
    groupRole.setRoles(new ArrayList<>(roleIdsById.values()));
    groupRoleRepository.save(groupRole);

    List<RoleSummary> roleSummaries =
        groupRole.getRoles().stream()
            .map(roleRepository::findById)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(
                role ->
                    RoleSummary.builder().id(role.getIdAsString()).code(role.getCode()).name(role.getName()).build())
            .toList();

    return GroupRoleResponse.builder()
        .groupId(groupId)
        .name(groupRole.getName())
        .code(groupRole.getCode())
        .roles(roleSummaries)
        .build();
  }

  private GroupResponse toResponse(Group group) {
    Group.GroupStatus status = group.getStatus();
    return GroupResponse.builder()
        .id(group.getIdAsString())
        .name(group.getName())
        .code(group.getCode())
        .groupType(group.getGroupType())
        .groupDescription(group.getGroupDescription())
        .statusCode(status != null ? status.getStatusCode() : null)
        .statusDescription(status != null ? status.getStatusDescription() : null)
        .build();
  }
}
