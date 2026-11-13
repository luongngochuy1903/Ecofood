package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.GroupEntity;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.UserGroupEntity;
import com.ecofood.app.userACL.repository.GroupEntityRepository;
import com.ecofood.app.userACL.repository.GroupRepository;
import com.ecofood.app.userACL.repository.UserGroupEntityRepository;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.CreateDataAccessRuleRequest;
import com.ecofood.core.dto.DataAccessRuleResponse;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.entity.RoleLevel;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import com.ecofood.core.enums.AccessLevelEnum;
import com.ecofood.core.enums.GroupType;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import com.ecofood.core.service.SequenceGeneratorService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class DataAccessRuleServiceImplTest {

  @Mock private GroupRepository groupRepository;
  @Mock private GroupEntityRepository groupEntityRepository;
  @Mock private UserGroupEntityRepository userGroupEntityRepository;
  @Mock private UserRepository userRepository;
  @Mock private CoreTenantQueryClient tenantQueryClient;
  @Mock private SequenceGeneratorService sequenceGeneratorService;
  @Mock private ACLService aclService;

  // 32+ raw bytes, base64-encoded — matches AuthenticationContext's documented
  // >=32-byte HS256 requirement (see UserServiceImplTest).
  private static final String SECRET =
      java.util.Base64.getEncoder().encodeToString("unit-test-only-hmac-secret-0123456789".getBytes());

  private DataAccessRuleServiceImpl dataAccessRuleService;

  private void setUp() {
    dataAccessRuleService =
        new DataAccessRuleServiceImpl(
            groupRepository, groupEntityRepository, userGroupEntityRepository, userRepository,
            tenantQueryClient, sequenceGeneratorService, aclService);
  }

  @AfterEach
  void unloadAuthenticationContext() {
    AuthenticationContext.unload();
  }

  /** listDirectory reads the caller's tenantId off AuthenticationContext, same as a real request would. */
  private void authenticateWithTenantId(String tenantId) {
    SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    String token =
        Jwts.builder()
            .claim("tenantId", tenantId)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(key)
            .compact();
    AuthenticationContext.setToken(token);
    AuthenticationContext.setSecret(SECRET);
  }

  /** getById/update read the caller's userId off AuthenticationContext for the ownership check. */
  private void authenticateAsUser(String userId) {
    SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    String token =
        Jwts.builder()
            .subject(userId)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(key)
            .compact();
    AuthenticationContext.setToken(token);
    AuthenticationContext.setSecret(SECRET);
  }

  private void stubGroupSaveAssignsId() {
    when(groupRepository.save(any(Group.class)))
        .thenAnswer(
            invocation -> {
              Group group = invocation.getArgument(0);
              if (group.getId() == null) {
                group.setId(new ObjectId());
              }
              return group;
            });
  }

  private CreateDataAccessRuleRequest baseRequest(String tenantId) {
    CreateDataAccessRuleRequest request = new CreateDataAccessRuleRequest();
    request.setBusinessEntityId(tenantId);
    request.setRuleName("Billing Viewer");
    request.setDescription("Lets partners view billing dashboards.");
    request.setAccessLevel(AccessLevelEnum.FULL_ACCESS);
    return request;
  }

  @Test
  void createRejectsCustomerLevelCallers() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.CUSTOMER);

    assertThatThrownBy(() -> dataAccessRuleService.create(baseRequest("t1")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createBuildsAnAccessControlGroupScopedToTheBusinessEntityTenant() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(sequenceGeneratorService.generateSequence(Group.SEQUENCE_NAME)).thenReturn(1L);
    TenantResponse tenant = TenantResponse.builder().id(new ObjectId().toHexString()).tenantName("Globe").build();
    when(tenantQueryClient.getById(tenant.getId())).thenReturn(tenant);
    stubGroupSaveAssignsId();

    CreateDataAccessRuleRequest request = baseRequest(tenant.getId());
    request.setAccessEntityType(AccessEntityTypeEnum.Product);

    DataAccessRuleResponse response = dataAccessRuleService.create(request);

    assertThat(response.getCode()).isEqualTo("DAR0001");
    assertThat(response.getRuleName()).isEqualTo("Billing Viewer");
    assertThat(response.getBusinessEntityId()).isEqualTo(tenant.getId());
    assertThat(response.getAccessLevel()).isEqualTo(AccessLevelEnum.FULL_ACCESS);
    assertThat(response.getAssignedUserCount()).isZero();

    ArgumentCaptor<Group> groupCaptor = ArgumentCaptor.forClass(Group.class);
    verify(groupRepository).save(groupCaptor.capture());
    assertThat(groupCaptor.getValue().getPartyRelationships()).hasSize(1);
    assertThat(groupCaptor.getValue().getPartyRelationships().get(0).getToPartyId())
        .isEqualTo(new ObjectId(tenant.getId()));
  }

  @Test
  void createSavesExactlyOneGroupEntityForTheSelectedType() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(sequenceGeneratorService.generateSequence(Group.SEQUENCE_NAME)).thenReturn(2L);
    TenantResponse tenant = TenantResponse.builder().id(new ObjectId().toHexString()).tenantName("Globe").build();
    when(tenantQueryClient.getById(tenant.getId())).thenReturn(tenant);
    stubGroupSaveAssignsId();

    CreateDataAccessRuleRequest request = baseRequest(tenant.getId());
    request.setAccessEntityType(AccessEntityTypeEnum.Dashboard_bills);

    dataAccessRuleService.create(request);

    ArgumentCaptor<GroupEntity> captor = ArgumentCaptor.forClass(GroupEntity.class);
    verify(groupEntityRepository).save(captor.capture());
    assertThat(captor.getValue().getAccessEntityType()).isEqualTo(AccessEntityTypeEnum.Dashboard_bills);
  }

  @Test
  void createSkipsGroupEntityWhenNoEntityTypeIsSelected() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(sequenceGeneratorService.generateSequence(Group.SEQUENCE_NAME)).thenReturn(2L);
    TenantResponse tenant = TenantResponse.builder().id(new ObjectId().toHexString()).tenantName("Globe").build();
    when(tenantQueryClient.getById(tenant.getId())).thenReturn(tenant);
    stubGroupSaveAssignsId();

    dataAccessRuleService.create(baseRequest(tenant.getId()));

    verify(groupEntityRepository, never()).save(any(GroupEntity.class));
  }

  @Test
  void createOnlyPopulatesGroupEntityEntityIdsWhenAccessLevelIsLimited() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(sequenceGeneratorService.generateSequence(Group.SEQUENCE_NAME)).thenReturn(3L);
    TenantResponse tenant = TenantResponse.builder().id(new ObjectId().toHexString()).tenantName("Globe").build();
    when(tenantQueryClient.getById(tenant.getId())).thenReturn(tenant);
    stubGroupSaveAssignsId();

    ObjectId limitedId = new ObjectId();
    CreateDataAccessRuleRequest request = baseRequest(tenant.getId());
    request.setAccessLevel(AccessLevelEnum.LIMITED_ACCESS);
    request.setAccessEntityType(AccessEntityTypeEnum.Product);
    request.setLimitedEntityIds(List.of(limitedId.toHexString()));

    dataAccessRuleService.create(request);

    ArgumentCaptor<GroupEntity> captor = ArgumentCaptor.forClass(GroupEntity.class);
    verify(groupEntityRepository).save(captor.capture());
    assertThat(captor.getValue().getEntityIds()).containsExactly(limitedId);
  }

  @Test
  void createLinksEachSelectedUserToANewUserGroupEntity() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(sequenceGeneratorService.generateSequence(Group.SEQUENCE_NAME)).thenReturn(4L);
    TenantResponse tenant = TenantResponse.builder().id(new ObjectId().toHexString()).tenantName("Globe").build();
    when(tenantQueryClient.getById(tenant.getId())).thenReturn(tenant);
    stubGroupSaveAssignsId();

    ObjectId userId = new ObjectId();
    UserEntity user = UserEntity.builder().username("jane.doe").build();
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userGroupEntityRepository.findByUserIdentifier(userId)).thenReturn(Optional.empty());

    CreateDataAccessRuleRequest request = baseRequest(tenant.getId());
    request.setUserIds(List.of(userId.toHexString()));

    DataAccessRuleResponse response = dataAccessRuleService.create(request);

    assertThat(response.getAssignedUserCount()).isEqualTo(1);
    ArgumentCaptor<UserGroupEntity> captor = ArgumentCaptor.forClass(UserGroupEntity.class);
    verify(userGroupEntityRepository).save(captor.capture());
    assertThat(captor.getValue().getUserIdentifier()).isEqualTo(userId);
    assertThat(captor.getValue().getGroups()).hasSize(1);
    assertThat(captor.getValue().getGroups().get(0).getGroupName()).isEqualTo("Billing Viewer");
  }

  @Test
  void createThrowsWhenAUserIdDoesNotExist() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(sequenceGeneratorService.generateSequence(Group.SEQUENCE_NAME)).thenReturn(5L);
    TenantResponse tenant = TenantResponse.builder().id(new ObjectId().toHexString()).tenantName("Globe").build();
    when(tenantQueryClient.getById(tenant.getId())).thenReturn(tenant);
    stubGroupSaveAssignsId();

    ObjectId missingUserId = new ObjectId();
    when(userRepository.findById(missingUserId)).thenReturn(Optional.empty());

    CreateDataAccessRuleRequest request = baseRequest(tenant.getId());
    request.setUserIds(List.of(missingUserId.toHexString()));

    assertThatThrownBy(() -> dataAccessRuleService.create(request)).isInstanceOf(IllegalArgumentException.class);
    verify(userGroupEntityRepository, never()).save(any(UserGroupEntity.class));
  }

  // --------------------------------------------------------- listDirectory

  @Test
  void listDirectoryRejectsCustomerLevelCallers() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.CUSTOMER);

    assertThatThrownBy(() -> dataAccessRuleService.listDirectory(0, 20, null, null))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void listDirectorySystemLevelIsUnrestricted() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    Group group = tenantScopedGroup("Billing Viewer", "DAR0001");
    when(groupRepository.findDirectory(eq(GroupType.ACCESS_CONTROL_GROUP), eq(List.of()), eq(true), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(group), PageRequest.of(0, 20), 1));

    PageResponse<com.ecofood.core.dto.DataAccessRuleDirectoryResponse> result =
        dataAccessRuleService.listDirectory(0, 20, null, null);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getCode()).isEqualTo("DAR0001");
    assertThat(result.getContent().get(0).getBusinessEntityName()).isEqualTo("Globe");
    verify(tenantQueryClient, never()).getScopedTenantIds(anyString());
  }

  @Test
  void listDirectoryTenantLevelScopesToItsOwnTenantWithoutQueryingTenantModule() {
    setUp();
    String callerTenantId = new ObjectId().toHexString();
    authenticateWithTenantId(callerTenantId);
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.TENANT);
    when(groupRepository.findDirectory(
            eq(GroupType.ACCESS_CONTROL_GROUP), eq(List.of(new ObjectId(callerTenantId))), eq(false), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    dataAccessRuleService.listDirectory(0, 20, null, null);

    verify(tenantQueryClient, never()).getScopedTenantIds(anyString());
  }

  @Test
  void listDirectoryEnterpriseLevelScopesToSelfPlusSubTenants() {
    setUp();
    String callerTenantId = new ObjectId().toHexString();
    String subTenantId = new ObjectId().toHexString();
    authenticateWithTenantId(callerTenantId);
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.ENTERPRISE);
    when(tenantQueryClient.getScopedTenantIds(callerTenantId)).thenReturn(List.of(callerTenantId, subTenantId));
    when(groupRepository.findDirectory(eq(GroupType.ACCESS_CONTROL_GROUP), any(), eq(false), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    dataAccessRuleService.listDirectory(0, 20, null, null);

    verify(tenantQueryClient).getScopedTenantIds(callerTenantId);
  }

  @Test
  void listDirectoryRejectsAnUnsupportedSortField() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);

    assertThatThrownBy(() -> dataAccessRuleService.listDirectory(0, 20, "notAField,asc", null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void listDirectoryDefaultsToUpdatedAtDescWhenSortIsBlank() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
        ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
    when(groupRepository.findDirectory(eq(GroupType.ACCESS_CONTROL_GROUP), eq(List.of()), eq(true), isNull(), pageableCaptor.capture()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    dataAccessRuleService.listDirectory(0, 20, null, null);

    Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("updatedAt");
    assertThat(order).isNotNull();
    assertThat(order.isDescending()).isTrue();
  }

  private Group tenantScopedGroup(String ruleName, String code) {
    Group group = Group.builder()
        .name(ruleName)
        .code(code)
        .groupType(GroupType.ACCESS_CONTROL_GROUP)
        .accessLevel(AccessLevelEnum.FULL_ACCESS)
        .partyRelationships(
            List.of(
                com.ecofood.app.userACL.entity.PartyToPartyRelationship.builder()
                    .toPartyId(new ObjectId())
                    .relationshipTypeCode(RelationshipTypeEnum.TENANT.getCode())
                    .relationshipReferenceName("Globe")
                    .build()))
        .build();
    group.setId(new ObjectId());
    return group;
  }

  // --------------------------------------------------------- getById

  @Test
  void getByIdRejectsCustomerLevelCallers() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.CUSTOMER);

    assertThatThrownBy(() -> dataAccessRuleService.getById(new ObjectId().toHexString()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void getByIdReturnsTheFullDetailIncludingEditableWhenTheCallerIsTheCreator() {
    setUp();
    String ownerId = new ObjectId().toHexString();
    authenticateAsUser(ownerId);
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);

    Group group = tenantScopedGroup("Billing Viewer", "DAR0001");
    group.setCreatedBy(ownerId);
    when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
    when(groupEntityRepository.findAllByGroupIdentifier(group.getId())).thenReturn(List.of());
    when(userGroupEntityRepository.findAllByGroups_GroupIdentifier(group.getId())).thenReturn(List.of());

    var detail = dataAccessRuleService.getById(group.getIdAsString());

    assertThat(detail.getRuleName()).isEqualTo("Billing Viewer");
    assertThat(detail.getBusinessEntityName()).isEqualTo("Globe");
    assertThat(detail.isEditable()).isTrue();
  }

  @Test
  void getByIdMarksNotEditableWhenTheCallerIsNotTheCreator() {
    setUp();
    authenticateAsUser(new ObjectId().toHexString());
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);

    Group group = tenantScopedGroup("Billing Viewer", "DAR0001");
    group.setCreatedBy(new ObjectId().toHexString());
    when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
    when(groupEntityRepository.findAllByGroupIdentifier(group.getId())).thenReturn(List.of());
    when(userGroupEntityRepository.findAllByGroups_GroupIdentifier(group.getId())).thenReturn(List.of());

    var detail = dataAccessRuleService.getById(group.getIdAsString());

    assertThat(detail.isEditable()).isFalse();
  }

  @Test
  void getByIdThrowsWhenTheGroupIsARoleGroupNotAnAccessControlGroup() {
    setUp();
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);

    Group roleGroup = Group.builder().name("MASTER_TENANT").groupType(GroupType.ROLE_GROUP).build();
    roleGroup.setId(new ObjectId());
    when(groupRepository.findById(roleGroup.getId())).thenReturn(Optional.of(roleGroup));

    assertThatThrownBy(() -> dataAccessRuleService.getById(roleGroup.getIdAsString()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --------------------------------------------------------- update

  @Test
  void updateRejectsACallerWhoIsNotTheCreator() {
    setUp();
    authenticateAsUser(new ObjectId().toHexString());

    Group group = tenantScopedGroup("Billing Viewer", "DAR0001");
    group.setCreatedBy(new ObjectId().toHexString());
    when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));

    com.ecofood.core.dto.UpdateDataAccessRuleRequest request = new com.ecofood.core.dto.UpdateDataAccessRuleRequest();
    request.setRuleName("Renamed");
    request.setDescription("desc");
    request.setAccessLevel(AccessLevelEnum.FULL_ACCESS);

    assertThatThrownBy(() -> dataAccessRuleService.update(group.getIdAsString(), request))
        .isInstanceOf(ForbiddenException.class);
    verify(groupRepository, never()).save(any(Group.class));
  }

  @Test
  void updateAppliesTheChangesWhenTheCallerIsTheCreator() {
    setUp();
    String ownerId = new ObjectId().toHexString();
    authenticateAsUser(ownerId);

    Group group = tenantScopedGroup("Billing Viewer", "DAR0001");
    group.setCreatedBy(ownerId);
    when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
    when(groupEntityRepository.findAllByGroupIdentifier(group.getId())).thenReturn(List.of());
    when(userGroupEntityRepository.findAllByGroups_GroupIdentifier(group.getId())).thenReturn(List.of());

    com.ecofood.core.dto.UpdateDataAccessRuleRequest request = new com.ecofood.core.dto.UpdateDataAccessRuleRequest();
    request.setRuleName("Renamed Rule");
    request.setDescription("Updated description");
    request.setAccessLevel(AccessLevelEnum.FULL_ACCESS);
    request.setAccessEntityType(AccessEntityTypeEnum.Product);

    var detail = dataAccessRuleService.update(group.getIdAsString(), request);

    assertThat(detail.getRuleName()).isEqualTo("Renamed Rule");
    assertThat(group.getName()).isEqualTo("Renamed Rule");
    assertThat(group.getGroupDescription()).isEqualTo("Updated description");
    verify(groupRepository).save(group);
    verify(groupEntityRepository).save(any(GroupEntity.class));
  }

  @Test
  void updateRevokesUsersNoLongerInTheDesiredList() {
    setUp();
    String ownerId = new ObjectId().toHexString();
    authenticateAsUser(ownerId);

    Group group = tenantScopedGroup("Billing Viewer", "DAR0001");
    group.setCreatedBy(ownerId);
    when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
    when(groupEntityRepository.findAllByGroupIdentifier(group.getId())).thenReturn(List.of());

    ObjectId removedUserId = new ObjectId();
    UserGroupEntity removedUserGroup =
        UserGroupEntity.builder()
            .userIdentifier(removedUserId)
            .groups(
                new java.util.ArrayList<>(
                    List.of(
                        UserGroupEntity.EmbeddedRestrictionGroup.builder().groupIdentifier(group.getId()).build())))
            .build();
    when(userGroupEntityRepository.findAllByGroups_GroupIdentifier(group.getId())).thenReturn(List.of(removedUserGroup));

    com.ecofood.core.dto.UpdateDataAccessRuleRequest request = new com.ecofood.core.dto.UpdateDataAccessRuleRequest();
    request.setRuleName("Billing Viewer");
    request.setDescription("desc");
    request.setAccessLevel(AccessLevelEnum.FULL_ACCESS);
    request.setUserIds(List.of());

    dataAccessRuleService.update(group.getIdAsString(), request);

    assertThat(removedUserGroup.getGroups()).isEmpty();
    verify(userGroupEntityRepository).save(removedUserGroup);
  }
}
