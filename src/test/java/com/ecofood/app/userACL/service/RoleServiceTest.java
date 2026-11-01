package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.userACL.dto.RoleCreateRequest;
import com.ecofood.app.userACL.dto.RoleResponse;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.entity.RoleType;
import com.ecofood.app.userACL.repository.RoleRepository;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

  @Mock private RoleRepository roleRepository;

  private RoleService roleService;

  @BeforeEach
  void setUp() {
    roleService = new RoleService(roleRepository);
  }

  private void stubRoleSaveAssignsId() {
    when(roleRepository.save(any(Role.class)))
        .thenAnswer(
            invocation -> {
              Role role = invocation.getArgument(0);
              if (role.getId() == null) {
                role.setId(new ObjectId());
              }
              return role;
            });
  }

  @Test
  void listByActorReturnsOnlyRolesPrefixedByThatActor() {
    Role role =
        Role.builder().name("Tenant Admin").code("TENANT_ADMIN").roleType(RoleType.Administrative).build();
    role.setId(new ObjectId());
    when(roleRepository.findByCodeStartingWith("TENANT_")).thenReturn(List.of(role));

    List<RoleResponse> result = roleService.listByActor("TENANT");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getCode()).isEqualTo("TENANT_ADMIN");
  }

  @Test
  void listByActorRejectsAnUnknownActor() {
    assertThatThrownBy(() -> roleService.listByActor("NOT_AN_ACTOR")).isInstanceOf(BadRequestException.class);
  }

  @Test
  void createsARoleWithoutStatusWhenNeitherStatusFieldIsProvided() {
    stubRoleSaveAssignsId();
    RoleCreateRequest request = new RoleCreateRequest();
    request.setName("Tenant Admin");
    request.setCode("TENANT_ADMIN");
    request.setRoleType(RoleType.Administrative);

    RoleResponse response = roleService.createRole(request);

    assertThat(response.getId()).isNotBlank();
    assertThat(response.getName()).isEqualTo("Tenant Admin");
    assertThat(response.getCode()).isEqualTo("TENANT_ADMIN");
    assertThat(response.getRoleType()).isEqualTo(RoleType.Administrative);
    assertThat(response.getStatusCode()).isNull();
    assertThat(response.getStatusDescription()).isNull();
  }

  @Test
  void createsARoleWithStatusWhenStatusCodeIsProvided() {
    stubRoleSaveAssignsId();
    RoleCreateRequest request = new RoleCreateRequest();
    request.setName("Tenant Admin");
    request.setCode("TENANT_ADMIN");
    request.setRoleType(RoleType.Administrative);
    request.setStatusCode("ACTIVE");
    request.setStatusDescription("Active");

    RoleResponse response = roleService.createRole(request);

    assertThat(response.getStatusCode()).isEqualTo("ACTIVE");
    assertThat(response.getStatusDescription()).isEqualTo("Active");
  }
}
