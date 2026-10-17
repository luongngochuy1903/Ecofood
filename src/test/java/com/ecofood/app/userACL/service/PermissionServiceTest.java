package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.app.userACL.dto.AddPermissionsToRoleRequest;
import com.ecofood.app.userACL.dto.PermissionCreateRequest;
import com.ecofood.app.userACL.dto.PermissionWithRoleRequest;
import com.ecofood.app.userACL.dto.PermissionWithRoleResponse;
import com.ecofood.app.userACL.dto.RolePermissionResponse;
import com.ecofood.app.userACL.entity.Permission;
import com.ecofood.app.userACL.repository.PermissionRepository;
import com.ecofood.app.userACL.repository.RoleRepository;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

  @Mock private PermissionRepository permissionRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private RolePermissionService rolePermissionService;

  private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    permissionService = new PermissionService(permissionRepository, roleRepository, rolePermissionService);
  }

  private void stubPermissionSaveAssignsId() {
    when(permissionRepository.save(any(Permission.class)))
        .thenAnswer(
            invocation -> {
              Permission permission = invocation.getArgument(0);
              if (permission.getId() == null) {
                permission.setId(new ObjectId());
              }
              return permission;
            });
  }

  private PermissionWithRoleRequest requestWithPermissions(String roleId, String... codes) {
    PermissionWithRoleRequest request = new PermissionWithRoleRequest();
    request.setRoleId(roleId);
    request.setPermissions(
        java.util.Arrays.stream(codes)
            .map(
                code -> {
                  PermissionCreateRequest p = new PermissionCreateRequest();
                  p.setName(code);
                  p.setCode(code);
                  return p;
                })
            .toList());
    return request;
  }

  @Test
  void createsEveryPermissionThenLinksAllOfThemToTheExistingRoleInOneCall() {
    stubPermissionSaveAssignsId();
    String roleId = new ObjectId().toHexString();
    when(roleRepository.existsById(new ObjectId(roleId))).thenReturn(true);
    when(rolePermissionService.addPermissionsToRole(anyString(), any()))
        .thenReturn(RolePermissionResponse.builder().roleId(roleId).permissions(List.of()).build());

    PermissionWithRoleResponse response =
        permissionService.createPermissionsWithRole(requestWithPermissions(roleId, "products.read.any", "products.write.any"));

    assertThat(response.getPermissions()).hasSize(2);
    assertThat(response.getPermissions().get(0).getCode()).isEqualTo("products.read.any");

    ArgumentCaptor<AddPermissionsToRoleRequest> captor = ArgumentCaptor.forClass(AddPermissionsToRoleRequest.class);
    verify(rolePermissionService).addPermissionsToRole(org.mockito.ArgumentMatchers.eq(roleId), captor.capture());
    assertThat(captor.getValue().getPermissionIds()).hasSize(2);
  }

  @Test
  void throwsBeforeCreatingAnyPermissionWhenRoleDoesNotExist() {
    String missingRoleId = new ObjectId().toHexString();
    when(roleRepository.existsById(new ObjectId(missingRoleId))).thenReturn(false);

    assertThatThrownBy(
            () -> permissionService.createPermissionsWithRole(requestWithPermissions(missingRoleId, "products.read.any")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missingRoleId);

    verify(permissionRepository, never()).save(any());
    verify(rolePermissionService, never()).addPermissionsToRole(anyString(), any());
  }
}
