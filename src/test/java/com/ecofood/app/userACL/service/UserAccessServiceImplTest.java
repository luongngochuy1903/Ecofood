package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ecofood.app.userACL.entity.GroupRole;
import com.ecofood.app.userACL.entity.RolePermission;
import com.ecofood.app.userACL.entity.UserRole;
import com.ecofood.app.userACL.repository.GroupRoleRepository;
import com.ecofood.app.userACL.repository.RolePermissionRepository;
import com.ecofood.app.userACL.repository.UserRoleRepository;
import com.ecofood.core.enums.RoleType;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserAccessServiceImplTest {

  @Mock private UserRoleRepository userRoleRepository;
  @Mock private RolePermissionRepository rolePermissionRepository;
  @Mock private GroupRoleRepository groupRoleRepository;

  private UserAccessServiceImpl userAccessService;

  private UserAccessServiceImpl service() {
    return new UserAccessServiceImpl(userRoleRepository, rolePermissionRepository, groupRoleRepository);
  }

  @Test
  void returnsEmptyClaimsWhenUserHasNoUserRoleForTheTenant() {
    userAccessService = service();
    ObjectId userId = new ObjectId();
    ObjectId tenantId = new ObjectId();
    when(userRoleRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(Optional.empty());

    UserAccessClaims claims = userAccessService.resolveAccess(userId, tenantId);

    assertThat(claims.roles()).isEmpty();
    assertThat(claims.permissions()).isEmpty();
    assertThat(claims.roleGroups()).isEmpty();
  }

  @Test
  void resolvesRolesFlattenedPermissionsAndOverlappingRoleGroups() {
    userAccessService = service();
    ObjectId userId = new ObjectId();
    ObjectId tenantId = new ObjectId();
    ObjectId roleId1 = new ObjectId();
    ObjectId roleId2 = new ObjectId();

    UserRole userRole =
        UserRole.builder()
            .userId(userId)
            .tenantId(tenantId)
            .roles(
                List.of(
                    UserRole.EmbeddedRole.builder().id(roleId1).code("TENANT_ADMIN").name("Tenant Admin").roleType(RoleType.Administrative).build(),
                    UserRole.EmbeddedRole.builder().id(roleId2).code("VIEWER").name("Viewer").roleType(RoleType.Functional).build()))
            .build();
    when(userRoleRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(Optional.of(userRole));

    RolePermission rolePermission1 =
        RolePermission.builder()
            .roleId(roleId1)
            .permissions(
                List.of(
                    RolePermission.EmbeddedPermission.builder().id(new ObjectId()).code("tenants.write.any").name("Write").build()))
            .build();
    RolePermission rolePermission2 =
        RolePermission.builder()
            .roleId(roleId2)
            .permissions(
                List.of(
                    RolePermission.EmbeddedPermission.builder().id(new ObjectId()).code("tenants.read.any").name("Read").build()))
            .build();
    when(rolePermissionRepository.findByRoleId(roleId1)).thenReturn(Optional.of(rolePermission1));
    when(rolePermissionRepository.findByRoleId(roleId2)).thenReturn(Optional.of(rolePermission2));

    GroupRole matchingGroupRole = GroupRole.builder().name("TENANT").roles(List.of(roleId1)).build();
    GroupRole nonMatchingGroupRole = GroupRole.builder().name("SYSTEM").roles(List.of(new ObjectId())).build();
    when(groupRoleRepository.findAll()).thenReturn(List.of(matchingGroupRole, nonMatchingGroupRole));

    UserAccessClaims claims = userAccessService.resolveAccess(userId, tenantId);

    assertThat(claims.roles()).containsExactlyInAnyOrder("TENANT_ADMIN", "VIEWER");
    assertThat(claims.permissions()).containsExactlyInAnyOrder("tenants.write.any", "tenants.read.any",
        "CATALOG_API_READ", "CATALOG_API_MANAGE", "CATALOG_API_PUBLISH", "CATALOG_API_GRANT", "TRANSACTION_SUBMIT", "DASHBOARD_READ",
        "NOTIFICATION_READ", "NOTIFICATION_RULE_MANAGE", "NOTIFICATION_RETRY");
    assertThat(claims.roleGroups()).containsExactly("TENANT");
  }

  @Test
  void returnsDefaultCatalogAndSubmitPermissionsForAdminWithoutRolePermissionDocument() {
    userAccessService = service();
    ObjectId userId = new ObjectId();
    ObjectId tenantId = new ObjectId();
    ObjectId roleId = new ObjectId();

    UserRole userRole =
        UserRole.builder()
            .userId(userId)
            .tenantId(tenantId)
            .roles(List.of(UserRole.EmbeddedRole.builder().id(roleId).code("TENANT_ADMIN").build()))
            .build();
    when(userRoleRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(Optional.of(userRole));
    when(rolePermissionRepository.findByRoleId(roleId)).thenReturn(Optional.empty());
    when(groupRoleRepository.findAll()).thenReturn(List.of());

    UserAccessClaims claims = userAccessService.resolveAccess(userId, tenantId);

    assertThat(claims.permissions()).containsExactlyInAnyOrder(
        "CATALOG_API_READ", "CATALOG_API_MANAGE", "CATALOG_API_PUBLISH", "CATALOG_API_GRANT", "TRANSACTION_SUBMIT", "DASHBOARD_READ",
        "NOTIFICATION_READ", "NOTIFICATION_RULE_MANAGE", "NOTIFICATION_RETRY");
    assertThat(claims.roleGroups()).isEmpty();
  }

  @Test
  void dashboardDefaultsCoverAllAdminRolesAndOnlyEnterpriseAdminReceivesPartnerPermission() {
    when(groupRoleRepository.findAll()).thenReturn(List.of());
    var service = service();
    for (String code : List.of("TENANT_ADMIN", "MASTER_ADMIN", "ENTERPRISE_ADMIN", "ECO_ADMIN", "TENANT_EMPLOYEE")) {
      var user = new ObjectId(); var tenant = new ObjectId(); var role = new ObjectId();
      var assignment = UserRole.builder().userId(user).tenantId(tenant)
          .roles(List.of(UserRole.EmbeddedRole.builder().id(role).code(code).build())).build();
      when(userRoleRepository.findByUserIdAndTenantId(user, tenant)).thenReturn(Optional.of(assignment));
      when(rolePermissionRepository.findByRoleId(role)).thenReturn(Optional.empty());
      var permissions = service.resolveAccess(user, tenant).permissions();
      if (code.endsWith("_ADMIN")) assertThat(permissions).contains("DASHBOARD_READ", "NOTIFICATION_READ", "NOTIFICATION_RULE_MANAGE", "NOTIFICATION_RETRY");
      else assertThat(permissions).doesNotContain("DASHBOARD_READ", "NOTIFICATION_READ", "NOTIFICATION_RULE_MANAGE", "NOTIFICATION_RETRY");
      if (code.equals("ENTERPRISE_ADMIN")) assertThat(permissions).contains("DASHBOARD_READ_PARTNERS");
      else assertThat(permissions).doesNotContain("DASHBOARD_READ_PARTNERS");
    }
  }
}
