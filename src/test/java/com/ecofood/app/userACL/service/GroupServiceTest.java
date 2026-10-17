package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.ecofood.app.userACL.dto.AddRolesToGroupRequest;
import com.ecofood.app.userACL.dto.GroupCreateRequest;
import com.ecofood.app.userACL.dto.GroupRoleResponse;
import com.ecofood.app.userACL.dto.GroupWithRolesRequest;
import com.ecofood.app.userACL.dto.GroupWithRolesResponse;
import com.ecofood.app.userACL.dto.RoleCreateRequest;
import com.ecofood.app.userACL.dto.RoleResponse;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.GroupRole;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.entity.RoleType;
import com.ecofood.app.userACL.repository.GroupRepository;
import com.ecofood.app.userACL.repository.GroupRoleRepository;
import com.ecofood.app.userACL.repository.RoleRepository;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

  @Mock private GroupRepository groupRepository;
  @Mock private GroupRoleRepository groupRoleRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private RoleService roleService;

  private GroupService groupService;

  @BeforeEach
  void setUp() {
    groupService = new GroupService(groupRepository, groupRoleRepository, roleRepository, roleService);
    lenient()
        .when(groupRepository.save(any(Group.class)))
        .thenAnswer(
            invocation -> {
              Group group = invocation.getArgument(0);
              if (group.getId() == null) {
                group.setId(new ObjectId());
              }
              return group;
            });
    lenient().when(groupRoleRepository.save(any(GroupRole.class))).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private Role roleEntity(String code) {
    Role role = Role.builder().name(code).code(code).roleType(RoleType.Administrative).build();
    role.setId(new ObjectId());
    return role;
  }

  @Test
  void createGroupWithRolesCreatesGroupEachRoleAndLinksThemIntoOneGroupRole() {
    GroupWithRolesRequest request = new GroupWithRolesRequest();
    GroupCreateRequest groupRequest = new GroupCreateRequest();
    groupRequest.setName("Tenant Admin Roles");
    groupRequest.setCode("TENANT");
    request.setGroup(groupRequest);

    RoleCreateRequest role1Request = new RoleCreateRequest();
    role1Request.setName("Tenant Admin");
    role1Request.setCode("TENANT_ADMIN");
    role1Request.setRoleType(RoleType.Administrative);
    request.setRoles(List.of(role1Request));

    Role savedRole = roleEntity("TENANT_ADMIN");
    when(roleService.createRole(role1Request))
        .thenReturn(
            RoleResponse.builder().id(savedRole.getIdAsString()).code("TENANT_ADMIN").name("Tenant Admin").build());
    when(groupRoleRepository.findByGroupId(any(ObjectId.class))).thenReturn(List.of());
    when(roleRepository.findById(savedRole.getId())).thenReturn(Optional.of(savedRole));
    // addRolesToGroup (reused internally) re-fetches the just-created group
    // by its auto-assigned id.
    when(groupRepository.findById(any(ObjectId.class)))
        .thenAnswer(
            invocation -> {
              Group found = Group.builder().name("Tenant Admin Roles").code("TENANT").build();
              found.setId(invocation.getArgument(0));
              return Optional.of(found);
            });

    GroupWithRolesResponse response = groupService.createGroupWithRoles(request);

    assertThat(response.getGroup().getCode()).isEqualTo("TENANT");
    assertThat(response.getGroupRole().getRoles()).hasSize(1);
    assertThat(response.getGroupRole().getRoles().get(0).getCode()).isEqualTo("TENANT_ADMIN");
  }

  @Test
  void addRolesToGroupIsAdditiveToAnExistingGroupRole() {
    ObjectId groupId = new ObjectId();
    Group group = Group.builder().name("Tenant Admin Roles").code("TENANT").build();
    group.setId(groupId);
    when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));

    Role existingRole = roleEntity("TENANT_ADMIN");
    GroupRole existingGroupRole =
        GroupRole.builder().groupId(groupId).name("Tenant Admin Roles").code("TENANT").roles(List.of(existingRole.getId())).build();
    when(groupRoleRepository.findByGroupId(groupId)).thenReturn(List.of(existingGroupRole));
    when(roleRepository.findById(existingRole.getId())).thenReturn(Optional.of(existingRole));

    Role newRole = roleEntity("ENTERPRISE_ADMIN");
    when(roleRepository.findById(newRole.getId())).thenReturn(Optional.of(newRole));

    GroupRoleResponse response =
        groupService.addRolesToGroup(
            groupId.toHexString(), AddRolesToGroupRequest.builder().roleIds(List.of(newRole.getId().toHexString())).build());

    assertThat(response.getRoles()).hasSize(2);
    assertThat(response.getRoles()).extracting("code").containsExactlyInAnyOrder("TENANT_ADMIN", "ENTERPRISE_ADMIN");
  }

  @Test
  void addRolesToGroupDedupesWhenTheSameRoleIsRequestedAgain() {
    ObjectId groupId = new ObjectId();
    Group group = Group.builder().name("Tenant Admin Roles").code("TENANT").build();
    group.setId(groupId);
    when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));
    when(groupRoleRepository.findByGroupId(groupId)).thenReturn(List.of());

    Role role = roleEntity("TENANT_ADMIN");
    when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));

    GroupRoleResponse response =
        groupService.addRolesToGroup(
            groupId.toHexString(),
            AddRolesToGroupRequest.builder().roleIds(List.of(role.getId().toHexString(), role.getId().toHexString())).build());

    assertThat(response.getRoles()).hasSize(1);
  }

  @Test
  void addRolesToGroupThrowsWhenGroupDoesNotExist() {
    ObjectId missingGroupId = new ObjectId();
    when(groupRepository.findById(missingGroupId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                groupService.addRolesToGroup(
                    missingGroupId.toHexString(), AddRolesToGroupRequest.builder().roleIds(List.of()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missingGroupId.toHexString());
  }

  @Test
  void addRolesToGroupThrowsWhenARoleIdDoesNotExist() {
    ObjectId groupId = new ObjectId();
    Group group = Group.builder().name("Tenant Admin Roles").code("TENANT").build();
    group.setId(groupId);
    when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));
    when(groupRoleRepository.findByGroupId(groupId)).thenReturn(List.of());
    ObjectId missingRoleId = new ObjectId();
    when(roleRepository.findById(missingRoleId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                groupService.addRolesToGroup(
                    groupId.toHexString(),
                    AddRolesToGroupRequest.builder().roleIds(List.of(missingRoleId.toHexString())).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missingRoleId.toHexString());
  }
}
