package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.core.client.CoreEnterpriseQueryClient;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.AssignTenantRolesRequest;
import com.ecofood.core.dto.AssignTenantRolesResponse;
import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.dto.TenantAssignmentRequest;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.dto.TenantRoleAssignmentRequest;
import com.ecofood.core.dto.UserCreateRequest;
import com.ecofood.core.dto.UserResponse;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.app.tenant.service.TenantKeycloakService;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.entity.RoleType;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.UserRole;
import com.ecofood.app.userACL.repository.RoleRepository;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.app.userACL.repository.UserRoleRepository;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.resource.RealmResource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

  @Mock private CoreTenantQueryClient tenantQueryClient;
  @Mock private CoreEnterpriseQueryClient enterpriseQueryClient;
  @Mock private TenantKeycloakService tenantKeycloakService;
  @Mock private UserKeycloakService userKeycloakService;
  @Mock private UserRepository userRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private UserRoleRepository userRoleRepository;
  @Mock private RealmResource realmResource;

  private UserServiceImpl userService;

  @BeforeEach
  void setUp() {
    userService =
        new UserServiceImpl(
            tenantQueryClient, enterpriseQueryClient, tenantKeycloakService, userKeycloakService, userRepository,
            roleRepository, userRoleRepository);
  }

  private void stubUserSaveAssignsId() {
    when(userRepository.save(any(UserEntity.class)))
        .thenAnswer(
            invocation -> {
              UserEntity user = invocation.getArgument(0);
              if (user.getId() == null) {
                user.setId(new ObjectId());
              }
              return user;
            });
  }

  private TenantResponse tenant(String name, ObjectId enterpriseId) {
    return TenantResponse.builder()
        .id(new ObjectId().toHexString())
        .tenantName(name)
        .realm(name.toLowerCase() + "-realm")
        .enterpriseId(enterpriseId != null ? enterpriseId.toHexString() : null)
        .build();
  }

  private void stubPrimaryKeycloakMirroring(TenantResponse tenant, String keycloakUserId) {
    when(tenantKeycloakService.realm(tenant.getRealm())).thenReturn(realmResource);
    when(userKeycloakService.createUser(org.mockito.ArgumentMatchers.eq(realmResource), any())).thenReturn(keycloakUserId);
  }

  // ---------------------------------------------------------------- createUser

  @Test
  void createUserMirrorsIntoKeycloakForTheFirstTenantAndBuildsOneRelation() {
    stubUserSaveAssignsId();
    TenantResponse acme = tenant("Acme", null);
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);
    stubPrimaryKeycloakMirroring(acme, "kc-user-1");

    UserCreateRequest request = new UserCreateRequest();
    request.setUsername("jane.doe");
    request.setEmail("jane@example.com");
    TenantAssignmentRequest assignment = new TenantAssignmentRequest();
    assignment.setTenantId(acme.getId());
    request.setTenantAssignments(List.of(assignment));

    UserResponse response = userService.createUser(request);

    assertThat(response.getTenantAssignments()).hasSize(1);
    assertThat(response.getTenantAssignments().get(0).getExternalLoginId()).isEqualTo("kc-user-1");
    assertThat(response.getTenantAssignments().get(0).getRelationshipTypeCode()).isEqualTo("TENANT");
    verify(userKeycloakService).sendVerifyEmail(realmResource, "kc-user-1");
  }

  @Test
  void createUserOnlyMirrorsTheFirstTenantAssignmentIntoKeycloak() {
    stubUserSaveAssignsId();
    TenantResponse acme = tenant("Acme", null);
    TenantResponse globex = tenant("Globex", null);
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);
    when(tenantQueryClient.getById(globex.getId())).thenReturn(globex);
    stubPrimaryKeycloakMirroring(acme, "kc-user-1");

    UserCreateRequest request = new UserCreateRequest();
    request.setUsername("jane.doe");
    request.setEmail("jane@example.com");
    TenantAssignmentRequest assignment1 = new TenantAssignmentRequest();
    assignment1.setTenantId(acme.getId());
    TenantAssignmentRequest assignment2 = new TenantAssignmentRequest();
    assignment2.setTenantId(globex.getId());
    request.setTenantAssignments(List.of(assignment1, assignment2));

    UserResponse response = userService.createUser(request);

    assertThat(response.getTenantAssignments().get(0).getExternalLoginId()).isEqualTo("kc-user-1");
    assertThat(response.getTenantAssignments().get(1).getExternalLoginId()).isNull();
    verify(userKeycloakService, times(1)).createUser(any(), any());
  }

  @Test
  void createUserDedupesARepeatedTenantIdInTheSameRequest() {
    stubUserSaveAssignsId();
    TenantResponse acme = tenant("Acme", null);
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);
    stubPrimaryKeycloakMirroring(acme, "kc-user-1");

    UserCreateRequest request = new UserCreateRequest();
    request.setUsername("jane.doe");
    request.setEmail("jane@example.com");
    TenantAssignmentRequest assignment1 = new TenantAssignmentRequest();
    assignment1.setTenantId(acme.getId());
    TenantAssignmentRequest assignment2 = new TenantAssignmentRequest();
    assignment2.setTenantId(acme.getId());
    request.setTenantAssignments(List.of(assignment1, assignment2));

    UserResponse response = userService.createUser(request);

    assertThat(response.getTenantAssignments()).hasSize(1);
  }

  @Test
  void createUserResolvesRolesWhenRoleIdsProvided() {
    stubUserSaveAssignsId();
    TenantResponse acme = tenant("Acme", null);
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);
    stubPrimaryKeycloakMirroring(acme, "kc-user-1");

    Role viewerRole = Role.builder().name("Viewer").code("VIEWER").roleType(RoleType.Functional).build();
    viewerRole.setId(new ObjectId());
    when(roleRepository.findById(viewerRole.getId())).thenReturn(Optional.of(viewerRole));
    when(userRoleRepository.findByUserIdAndTenantId(any(), any())).thenReturn(Optional.empty());
    when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

    UserCreateRequest request = new UserCreateRequest();
    request.setUsername("jane.doe");
    request.setEmail("jane@example.com");
    TenantAssignmentRequest assignment = new TenantAssignmentRequest();
    assignment.setTenantId(acme.getId());
    assignment.setRoleIds(List.of(viewerRole.getIdAsString()));
    request.setTenantAssignments(List.of(assignment));

    UserResponse response = userService.createUser(request);

    assertThat(response.getTenantAssignments().get(0).getRoleCodes()).containsExactly("VIEWER");
  }

  @Test
  void createUserAddsEnterpriseRelationWhenTenantHasAnEnterpriseId() {
    stubUserSaveAssignsId();
    ObjectId enterpriseId = new ObjectId();
    TenantResponse acme = tenant("Acme", enterpriseId);
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);
    stubPrimaryKeycloakMirroring(acme, "kc-user-1");
    when(enterpriseQueryClient.getById(enterpriseId.toHexString()))
        .thenReturn(EnterpriseResponse.builder().enterpriseId(enterpriseId.toHexString()).enterpriseCompanyName("Acme Holdings").build());

    UserCreateRequest request = new UserCreateRequest();
    request.setUsername("jane.doe");
    request.setEmail("jane@example.com");
    TenantAssignmentRequest assignment = new TenantAssignmentRequest();
    assignment.setTenantId(acme.getId());
    request.setTenantAssignments(List.of(assignment));

    userService.createUser(request);

    ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userRepository, times(2)).save(captor.capture());
    List<PartyToPartyRelationship> relations = captor.getValue().getPartyRelationships();
    assertThat(relations).hasSize(2);
    assertThat(relations.get(0).getRelationshipTypeCode()).isEqualTo("TENANT");
    assertThat(relations.get(1).getRelationshipTypeCode()).isEqualTo("ENTERPRISE");
    assertThat(relations.get(1).getRelationshipReferenceName()).isEqualTo("Acme Holdings");
  }

  @Test
  void createUserDoesNotAddEnterpriseRelationWhenTenantHasNoEnterpriseId() {
    stubUserSaveAssignsId();
    TenantResponse acme = tenant("Acme", null);
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);
    stubPrimaryKeycloakMirroring(acme, "kc-user-1");

    UserCreateRequest request = new UserCreateRequest();
    request.setUsername("jane.doe");
    request.setEmail("jane@example.com");
    TenantAssignmentRequest assignment = new TenantAssignmentRequest();
    assignment.setTenantId(acme.getId());
    request.setTenantAssignments(List.of(assignment));

    userService.createUser(request);

    verify(enterpriseQueryClient, never()).getById(anyString());
  }

  // ---------------------------------------------------------- assignTenantRoles

  @Test
  void assignTenantRolesAddsANewRelationWhenNotAlreadyAssigned() {
    ObjectId userId = new ObjectId();
    UserEntity user = UserEntity.builder().username("jane.doe").build();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    TenantResponse acme = tenant("Acme", null);
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);

    Role viewerRole = Role.builder().name("Viewer").code("VIEWER").roleType(RoleType.Functional).build();
    viewerRole.setId(new ObjectId());
    when(roleRepository.findById(viewerRole.getId())).thenReturn(Optional.of(viewerRole));
    when(userRoleRepository.findByUserIdAndTenantId(userId, new ObjectId(acme.getId()))).thenReturn(Optional.empty());
    when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TenantRoleAssignmentRequest assignment = new TenantRoleAssignmentRequest();
    assignment.setTenantId(acme.getId());
    assignment.setRoleIds(List.of(viewerRole.getIdAsString()));
    AssignTenantRolesRequest request = new AssignTenantRolesRequest();
    request.setTenantAssignments(List.of(assignment));

    AssignTenantRolesResponse response = userService.assignTenantRoles(userId.toHexString(), request);

    assertThat(response.getAssignments()).hasSize(1);
    assertThat(response.getAssignments().get(0).getRoleCodes()).containsExactly("VIEWER");
    assertThat(user.getPartyRelationships()).hasSize(1);
  }

  @Test
  void assignTenantRolesDoesNotDuplicateAnAlreadyAssignedRelation() {
    ObjectId userId = new ObjectId();
    TenantResponse acme = tenant("Acme", null);
    UserEntity user = UserEntity.builder().username("jane.doe").build();
    user.setId(userId);
    user.setPartyRelationships(
        new java.util.ArrayList<>(
            List.of(
                PartyToPartyRelationship.builder()
                    .toPartyId(new ObjectId(acme.getId()))
                    .relationshipTypeCode("TENANT")
                    .build())));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);
    when(userRoleRepository.findByUserIdAndTenantId(userId, new ObjectId(acme.getId()))).thenReturn(Optional.empty());
    when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TenantRoleAssignmentRequest assignment = new TenantRoleAssignmentRequest();
    assignment.setTenantId(acme.getId());
    assignment.setRoleIds(List.of());
    AssignTenantRolesRequest request = new AssignTenantRolesRequest();
    request.setTenantAssignments(List.of(assignment));

    userService.assignTenantRoles(userId.toHexString(), request);

    assertThat(user.getPartyRelationships()).hasSize(1);
  }

  @Test
  void assignTenantRolesAddsEnterpriseRelationOnceWhenTenantHasEnterpriseId() {
    ObjectId userId = new ObjectId();
    ObjectId enterpriseId = new ObjectId();
    TenantResponse acme = tenant("Acme", enterpriseId);
    UserEntity user = UserEntity.builder().username("jane.doe").build();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);
    when(enterpriseQueryClient.getById(enterpriseId.toHexString()))
        .thenReturn(EnterpriseResponse.builder().enterpriseId(enterpriseId.toHexString()).enterpriseCompanyName("Acme Holdings").build());
    when(userRoleRepository.findByUserIdAndTenantId(userId, new ObjectId(acme.getId()))).thenReturn(Optional.empty());
    when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TenantRoleAssignmentRequest assignment = new TenantRoleAssignmentRequest();
    assignment.setTenantId(acme.getId());
    assignment.setRoleIds(List.of());
    AssignTenantRolesRequest request = new AssignTenantRolesRequest();
    request.setTenantAssignments(List.of(assignment));

    userService.assignTenantRoles(userId.toHexString(), request);

    assertThat(user.getPartyRelationships()).hasSize(2);
    assertThat(user.getPartyRelationships().get(1).getRelationshipTypeCode()).isEqualTo("ENTERPRISE");
  }

  @Test
  void assignTenantRolesThrowsWhenUserDoesNotExist() {
    ObjectId missingUserId = new ObjectId();
    when(userRepository.findById(missingUserId)).thenReturn(Optional.empty());

    AssignTenantRolesRequest request = new AssignTenantRolesRequest();
    request.setTenantAssignments(List.of());

    assertThatThrownBy(() -> userService.assignTenantRoles(missingUserId.toHexString(), request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missingUserId.toHexString());
  }

  // -------------------------------------------------------------- resolveRoles

  @Test
  void resolveRolesReplacesPreviousRolesRatherThanMerging() {
    ObjectId userId = new ObjectId();
    TenantResponse acme = tenant("Acme", null);
    UserEntity user = UserEntity.builder().username("jane.doe").build();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);

    Role oldRole = Role.builder().name("Old").code("OLD_ROLE").roleType(RoleType.Functional).build();
    oldRole.setId(new ObjectId());
    Role newRole = Role.builder().name("New").code("NEW_ROLE").roleType(RoleType.Functional).build();
    newRole.setId(new ObjectId());

    UserRole existingUserRole =
        UserRole.builder()
            .userId(userId)
            .tenantId(new ObjectId(acme.getId()))
            .roles(
                List.of(
                    UserRole.EmbeddedRole.builder().id(oldRole.getId()).code("OLD_ROLE").roleType(RoleType.Functional).build()))
            .build();
    when(userRoleRepository.findByUserIdAndTenantId(userId, new ObjectId(acme.getId())))
        .thenReturn(Optional.of(existingUserRole));
    when(roleRepository.findById(newRole.getId())).thenReturn(Optional.of(newRole));
    when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TenantRoleAssignmentRequest assignment = new TenantRoleAssignmentRequest();
    assignment.setTenantId(acme.getId());
    assignment.setRoleIds(List.of(newRole.getIdAsString()));
    AssignTenantRolesRequest request = new AssignTenantRolesRequest();
    request.setTenantAssignments(List.of(assignment));

    AssignTenantRolesResponse response = userService.assignTenantRoles(userId.toHexString(), request);

    // Only NEW_ROLE should remain — OLD_ROLE was not re-requested, so it's
    // dropped, not kept alongside the new one.
    assertThat(response.getAssignments().get(0).getRoleCodes()).containsExactly("NEW_ROLE");
  }

  @Test
  void resolveRolesDedupesByCanonicalRoleIdEvenIfRequestedTwice() {
    ObjectId userId = new ObjectId();
    TenantResponse acme = tenant("Acme", null);
    UserEntity user = UserEntity.builder().username("jane.doe").build();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);

    Role role = Role.builder().name("Viewer").code("VIEWER").roleType(RoleType.Functional).build();
    role.setId(new ObjectId());
    when(userRoleRepository.findByUserIdAndTenantId(userId, new ObjectId(acme.getId()))).thenReturn(Optional.empty());
    when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));
    when(userRoleRepository.save(any(UserRole.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TenantRoleAssignmentRequest assignment = new TenantRoleAssignmentRequest();
    assignment.setTenantId(acme.getId());
    assignment.setRoleIds(List.of(role.getIdAsString(), role.getIdAsString()));
    AssignTenantRolesRequest request = new AssignTenantRolesRequest();
    request.setTenantAssignments(List.of(assignment));

    AssignTenantRolesResponse response = userService.assignTenantRoles(userId.toHexString(), request);

    assertThat(response.getAssignments().get(0).getRoleCodes()).containsExactly("VIEWER");
  }

  @Test
  void resolveRolesThrowsWhenARoleIdDoesNotExist() {
    ObjectId userId = new ObjectId();
    TenantResponse acme = tenant("Acme", null);
    UserEntity user = UserEntity.builder().username("jane.doe").build();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(tenantQueryClient.getById(acme.getId())).thenReturn(acme);
    when(userRoleRepository.findByUserIdAndTenantId(userId, new ObjectId(acme.getId()))).thenReturn(Optional.empty());
    ObjectId missingRoleId = new ObjectId();
    when(roleRepository.findById(missingRoleId)).thenReturn(Optional.empty());

    TenantRoleAssignmentRequest assignment = new TenantRoleAssignmentRequest();
    assignment.setTenantId(acme.getId());
    assignment.setRoleIds(List.of(missingRoleId.toHexString()));
    AssignTenantRolesRequest request = new AssignTenantRolesRequest();
    request.setTenantAssignments(List.of(assignment));

    assertThatThrownBy(() -> userService.assignTenantRoles(userId.toHexString(), request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missingRoleId.toHexString());
  }
}
