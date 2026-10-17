package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.app.userACL.entity.PartnerTypeEnum;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.entity.UserRole;
import com.ecofood.app.userACL.repository.RoleRepository;
import com.ecofood.app.userACL.repository.UserRoleRepository;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserRoleAssignmentServiceImplTest {

  @Mock private RoleRepository roleRepository;
  @Mock private UserRoleRepository userRoleRepository;

  private UserRoleAssignmentServiceImpl service() {
    return new UserRoleAssignmentServiceImpl(roleRepository, userRoleRepository);
  }

  private Role adminRole(String code) {
    Role role = Role.builder().name(code).code(code).build();
    role.setId(new ObjectId());
    return role;
  }

  @Test
  void enterprisePartnerTypeAssignsEnterpriseAdminRole() {
    Role enterpriseAdmin = adminRole("ENTERPRISE_ADMIN");
    when(roleRepository.findByCode("ENTERPRISE_ADMIN")).thenReturn(Optional.of(enterpriseAdmin));
    ObjectId userId = new ObjectId();
    ObjectId tenantId = new ObjectId();

    service().assignRoleForOnboarding(userId, tenantId, PartnerTypeEnum.ENTERPRISE);

    ArgumentCaptor<UserRole> captor = ArgumentCaptor.forClass(UserRole.class);
    verify(userRoleRepository).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    assertThat(captor.getValue().getTenantId()).isEqualTo(tenantId);
    assertThat(captor.getValue().getRoles()).hasSize(1);
    assertThat(captor.getValue().getRoles().get(0).getCode()).isEqualTo("ENTERPRISE_ADMIN");
  }

  @Test
  void regionalEnterprisePartnerTypeAlsoAssignsEnterpriseAdminRole() {
    when(roleRepository.findByCode("ENTERPRISE_ADMIN")).thenReturn(Optional.of(adminRole("ENTERPRISE_ADMIN")));

    service().assignRoleForOnboarding(new ObjectId(), new ObjectId(), PartnerTypeEnum.REGIONAL_ENTERPRISE);

    verify(roleRepository).findByCode("ENTERPRISE_ADMIN");
  }

  @Test
  void ecosystemPartnerPartnerTypeAssignsTenantAdminRole() {
    when(roleRepository.findByCode("TENANT_ADMIN")).thenReturn(Optional.of(adminRole("TENANT_ADMIN")));

    service().assignRoleForOnboarding(new ObjectId(), new ObjectId(), PartnerTypeEnum.ECOSYSTEM_PARTNER);

    verify(roleRepository).findByCode("TENANT_ADMIN");
  }

  @Test
  void brandPartnerTypeAlsoAssignsTenantAdminRole() {
    when(roleRepository.findByCode("TENANT_ADMIN")).thenReturn(Optional.of(adminRole("TENANT_ADMIN")));

    service().assignRoleForOnboarding(new ObjectId(), new ObjectId(), PartnerTypeEnum.BRAND);

    verify(roleRepository).findByCode("TENANT_ADMIN");
  }

  @Test
  void throwsWhenTheExpectedRoleHasNotBeenCreatedManuallyYet() {
    when(roleRepository.findByCode("TENANT_ADMIN")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service().assignRoleForOnboarding(new ObjectId(), new ObjectId(), PartnerTypeEnum.BRAND))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TENANT_ADMIN");

    verify(userRoleRepository, never()).save(any());
  }
}
