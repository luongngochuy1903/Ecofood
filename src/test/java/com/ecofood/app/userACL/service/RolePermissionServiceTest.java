package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.app.userACL.dto.AddPermissionsToRoleRequest;
import com.ecofood.app.userACL.dto.RolePermissionResponse;
import com.ecofood.app.userACL.entity.Permission;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.entity.RolePermission;
import com.ecofood.app.userACL.repository.PermissionRepository;
import com.ecofood.app.userACL.repository.RolePermissionRepository;
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
class RolePermissionServiceTest {

  @Mock private RoleRepository roleRepository;
  @Mock private PermissionRepository permissionRepository;
  @Mock private RolePermissionRepository rolePermissionRepository;

  private RolePermissionService rolePermissionService;
  private ObjectId roleId;

  @BeforeEach
  void setUp() {
    rolePermissionService = new RolePermissionService(roleRepository, permissionRepository, rolePermissionRepository);
    roleId = new ObjectId();
    Role role = Role.builder().code("TENANT_ADMIN").build();
    role.setId(roleId);
    // lenient: not every test (e.g. the role-not-found case) reaches these.
    lenient().when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
    lenient()
        .when(rolePermissionRepository.save(any(RolePermission.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private Permission permission(String code) {
    Permission permission = Permission.builder().name(code).code(code).build();
    permission.setId(new ObjectId());
    return permission;
  }

  @Test
  void createsANewRolePermissionDocWhenNoneExistsYet() {
    when(rolePermissionRepository.findByRoleId(roleId)).thenReturn(Optional.empty());
    Permission p1 = permission("products.read.any");
    when(permissionRepository.findById(p1.getId())).thenReturn(Optional.of(p1));

    RolePermissionResponse response =
        rolePermissionService.addPermissionsToRole(
            roleId.toHexString(),
            AddPermissionsToRoleRequest.builder().permissionIds(List.of(p1.getId().toHexString())).build());

    assertThat(response.getPermissions()).hasSize(1);
    assertThat(response.getPermissions().get(0).getCode()).isEqualTo("products.read.any");
  }

  @Test
  void isAdditiveNotReplacingAlreadyLinkedPermissions() {
    Permission existing = permission("products.read.any");
    RolePermission existingDoc =
        RolePermission.builder()
            .roleId(roleId)
            .permissions(
                List.of(
                    RolePermission.EmbeddedPermission.builder()
                        .id(existing.getId())
                        .code(existing.getCode())
                        .name(existing.getName())
                        .build()))
            .build();
    when(rolePermissionRepository.findByRoleId(roleId)).thenReturn(Optional.of(existingDoc));

    Permission newPermission = permission("products.write.any");
    when(permissionRepository.findById(newPermission.getId())).thenReturn(Optional.of(newPermission));

    RolePermissionResponse response =
        rolePermissionService.addPermissionsToRole(
            roleId.toHexString(),
            AddPermissionsToRoleRequest.builder().permissionIds(List.of(newPermission.getId().toHexString())).build());

    assertThat(response.getPermissions()).hasSize(2);
    assertThat(response.getPermissions())
        .extracting("code")
        .containsExactlyInAnyOrder("products.read.any", "products.write.any");
  }

  @Test
  void dedupesWhenTheSamePermissionIsRequestedAgain() {
    Permission existing = permission("products.read.any");
    RolePermission existingDoc =
        RolePermission.builder()
            .roleId(roleId)
            .permissions(
                List.of(
                    RolePermission.EmbeddedPermission.builder()
                        .id(existing.getId())
                        .code(existing.getCode())
                        .name(existing.getName())
                        .build()))
            .build();
    when(rolePermissionRepository.findByRoleId(roleId)).thenReturn(Optional.of(existingDoc));
    when(permissionRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

    RolePermissionResponse response =
        rolePermissionService.addPermissionsToRole(
            roleId.toHexString(),
            AddPermissionsToRoleRequest.builder().permissionIds(List.of(existing.getId().toHexString())).build());

    assertThat(response.getPermissions()).hasSize(1);
  }

  @Test
  void dedupesByCanonicalIdEvenWithDifferentlyCasedHexString() {
    when(rolePermissionRepository.findByRoleId(roleId)).thenReturn(Optional.empty());
    Permission p1 = permission("products.read.any");
    when(permissionRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
    String upperCaseHex = p1.getId().toHexString().toUpperCase();

    RolePermissionResponse response =
        rolePermissionService.addPermissionsToRole(
            roleId.toHexString(), AddPermissionsToRoleRequest.builder().permissionIds(List.of(upperCaseHex)).build());

    assertThat(response.getPermissions()).hasSize(1);
  }

  @Test
  void throwsWhenRoleDoesNotExist() {
    ObjectId missingRoleId = new ObjectId();
    when(roleRepository.findById(missingRoleId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                rolePermissionService.addPermissionsToRole(
                    missingRoleId.toHexString(), AddPermissionsToRoleRequest.builder().permissionIds(List.of()).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missingRoleId.toHexString());
  }

  @Test
  void throwsAndNeverSavesWhenAPermissionIdDoesNotExist() {
    when(rolePermissionRepository.findByRoleId(roleId)).thenReturn(Optional.empty());
    ObjectId missingPermissionId = new ObjectId();
    when(permissionRepository.findById(missingPermissionId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                rolePermissionService.addPermissionsToRole(
                    roleId.toHexString(),
                    AddPermissionsToRoleRequest.builder()
                        .permissionIds(List.of(missingPermissionId.toHexString()))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missingPermissionId.toHexString());

    verify(rolePermissionRepository, never()).save(any());
  }
}
