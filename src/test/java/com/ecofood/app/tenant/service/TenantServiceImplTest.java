package com.ecofood.app.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.core.client.CoreEnterpriseClient;
import com.ecofood.core.client.CoreUserClient;
import com.ecofood.core.client.CoreUserRoleAssignmentClient;
import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.dto.TenantOnboardRequest;
import com.ecofood.core.dto.TenantOnboardResponse;
import com.ecofood.core.dto.TenantPartyRelationshipRequest;
import com.ecofood.core.dto.UserResponse;
import com.ecofood.core.service.SequenceGeneratorService;
import com.ecofood.app.tenant.entity.TenantEntity;
import com.ecofood.app.tenant.repository.TenantRepository;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.enums.RelationshipTypeEnum;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantServiceImplTest {

  @Mock private TenantKeycloakService tenantKeycloakService;
  @Mock private TenantRepository tenantRepository;
  @Mock private CoreUserClient userClient;
  @Mock private SequenceGeneratorService sequenceGeneratorService;
  @Mock private CoreUserRoleAssignmentClient userRoleAssignmentClient;
  @Mock private CoreEnterpriseClient enterpriseClient;
  @Mock private RealmResource realmResource;

  private TenantServiceImpl tenantService;

  @BeforeEach
  void setUp() {
    tenantService =
        new TenantServiceImpl(
            tenantKeycloakService, tenantRepository, userClient, sequenceGeneratorService, userRoleAssignmentClient,
            enterpriseClient);
    // @Value fields — never populated outside a Spring context, so set
    // directly for this plain Mockito-based unit test.
    ReflectionTestUtils.setField(tenantService, "loginTheme", "app");
    ReflectionTestUtils.setField(tenantService, "redirectUri", "http://localhost:3000/*");
    ReflectionTestUtils.setField(tenantService, "webOrigin", "http://localhost:3000");
    ReflectionTestUtils.setField(tenantService, "smtpHost", "mailpit");
    ReflectionTestUtils.setField(tenantService, "smtpPort", "1025");
    ReflectionTestUtils.setField(tenantService, "smtpFrom", "no-reply@ecofood.local");
    ReflectionTestUtils.setField(tenantService, "smtpAuth", "false");
    ReflectionTestUtils.setField(tenantService, "smtpStarttls", "false");
    ReflectionTestUtils.setField(tenantService, "smtpUser", "");
    ReflectionTestUtils.setField(tenantService, "smtpPassword", "");
  }

  private TenantOnboardRequest basicRequest(PartnerTypeEnum partnerType) {
    TenantOnboardRequest request = new TenantOnboardRequest();
    request.setTenantName("Acme Foods");
    request.setUsername("acme.admin");
    request.setEmail("admin@acmefoods.com");
    request.setFirstName("Jane");
    request.setLastName("Doe");
    request.setDomainUrl("acmefoods.example.com");
    request.setPartnerTypeCode(partnerType);
    request.setPartnerTypeDescription("desc");
    return request;
  }

  // Simulates tenantRepository.save() auto-assigning a Mongo id on the
  // FIRST save only (real Spring Data behavior), same id on every
  // subsequent save of the same entity instance.
  private void stubTenantSaveAssignsId() {
    when(tenantRepository.save(any(TenantEntity.class)))
        .thenAnswer(
            invocation -> {
              TenantEntity tenant = invocation.getArgument(0);
              if (tenant.getId() == null) {
                tenant.setId(new ObjectId());
              }
              return tenant;
            });
  }

  private void stubKeycloakAndDownstreamCalls(String clientId, String userId) {
    when(tenantKeycloakService.realm(anyString())).thenReturn(realmResource);
    when(tenantKeycloakService.createClient(eq(realmResource), any(ClientRepresentation.class))).thenReturn(clientId);
    when(sequenceGeneratorService.generateSequence(TenantEntity.SEQUENCE_NAME)).thenReturn(1L);
    when(userClient.createUser(any())).thenReturn(UserResponse.builder().userId(userId).build());
  }

  @Test
  void onboardsEcosystemPartnerTenantWithoutCreatingAnEnterprise() {
    stubTenantSaveAssignsId();
    stubKeycloakAndDownstreamCalls("tenant-public", new ObjectId().toHexString());

    TenantOnboardResponse response = tenantService.onboardTenant(basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER));

    assertThat(response.getTenantName()).isEqualTo("Acme Foods");
    assertThat(response.getClientId()).isEqualTo("tenant-public");
    assertThat(response.getRealm()).startsWith("acme-foods-");

    verify(enterpriseClient, never()).createEnterprise(any());
    verify(tenantRepository, times(2)).save(any(TenantEntity.class));
  }

  @Test
  void generatesTenantCodeFromSequenceWithZeroPaddingAndPrefix() {
    stubTenantSaveAssignsId();
    stubKeycloakAndDownstreamCalls("tenant-public", new ObjectId().toHexString());
    when(sequenceGeneratorService.generateSequence(TenantEntity.SEQUENCE_NAME)).thenReturn(42L);

    tenantService.onboardTenant(basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER));

    ArgumentCaptor<TenantEntity> captor = ArgumentCaptor.forClass(TenantEntity.class);
    verify(tenantRepository, times(2)).save(captor.capture());
    assertThat(captor.getValue().getTenantCode()).isEqualTo("TE0042");
  }

  @Test
  void enterprisePartnerTypeCreatesCompanionEnterpriseAndLinksItsId() {
    stubTenantSaveAssignsId();
    stubKeycloakAndDownstreamCalls("tenant-public", new ObjectId().toHexString());
    String enterpriseId = new ObjectId().toHexString();
    when(enterpriseClient.createEnterprise(any()))
        .thenReturn(EnterpriseResponse.builder().enterpriseId(enterpriseId).enterpriseCompanyName("Acme Foods").build());

    tenantService.onboardTenant(basicRequest(PartnerTypeEnum.ENTERPRISE));

    ArgumentCaptor<TenantEntity> captor = ArgumentCaptor.forClass(TenantEntity.class);
    verify(tenantRepository, times(2)).save(captor.capture());
    assertThat(captor.getValue().getEnterpriseIdAsString()).isEqualTo(enterpriseId);
  }

  @Test
  void regionalEnterprisePartnerTypeAlsoCreatesCompanionEnterprise() {
    stubTenantSaveAssignsId();
    stubKeycloakAndDownstreamCalls("tenant-public", new ObjectId().toHexString());
    when(enterpriseClient.createEnterprise(any()))
        .thenReturn(EnterpriseResponse.builder().enterpriseId(new ObjectId().toHexString()).build());

    tenantService.onboardTenant(basicRequest(PartnerTypeEnum.REGIONAL_ENTERPRISE));

    verify(enterpriseClient, times(1)).createEnterprise(any());
  }

  @Test
  void onboardingAssignsOnboardingRoleForTheCreatedUserAndTenant() {
    stubTenantSaveAssignsId();
    String userId = new ObjectId().toHexString();
    stubKeycloakAndDownstreamCalls("tenant-public", userId);

    tenantService.onboardTenant(basicRequest(PartnerTypeEnum.BRAND));

    ArgumentCaptor<ObjectId> tenantIdCaptor = ArgumentCaptor.forClass(ObjectId.class);
    verify(userRoleAssignmentClient)
        .assignRoleForOnboarding(eq(new ObjectId(userId)), tenantIdCaptor.capture(), eq(PartnerTypeEnum.BRAND));
    assertThat(tenantIdCaptor.getValue()).isNotNull();
  }

  @Test
  void buildsTenantToTenantRelationshipAgainstAnExistingTenant() {
    stubTenantSaveAssignsId();
    stubKeycloakAndDownstreamCalls("tenant-public", new ObjectId().toHexString());

    TenantEntity masterTenant = TenantEntity.builder().tenantName("Master Co").build();
    ObjectId masterId = new ObjectId();
    masterTenant.setId(masterId);
    when(tenantRepository.findById(masterId)).thenReturn(Optional.of(masterTenant));

    TenantOnboardRequest request = basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER);
    TenantPartyRelationshipRequest relationshipRequest = new TenantPartyRelationshipRequest();
    relationshipRequest.setToTenantId(masterId.toHexString());
    relationshipRequest.setRelationshipTypeCode(RelationshipTypeEnum.SUB_TENANT);
    request.setPartyRelationships(List.of(relationshipRequest));

    tenantService.onboardTenant(request);

    ArgumentCaptor<TenantEntity> captor = ArgumentCaptor.forClass(TenantEntity.class);
    verify(tenantRepository, times(2)).save(captor.capture());
    TenantEntity savedTenant = captor.getAllValues().get(0);
    assertThat(savedTenant.getPartyRelationships()).hasSize(1);
    assertThat(savedTenant.getPartyRelationships().get(0).getRelationshipTypeCode()).isEqualTo("ST");
    assertThat(savedTenant.getPartyRelationships().get(0).getRelationshipReferenceName()).isEqualTo("Master Co");
  }

  @Test
  void throwsWhenRelatedTenantForPartyRelationshipDoesNotExist() {
    when(sequenceGeneratorService.generateSequence(TenantEntity.SEQUENCE_NAME)).thenReturn(1L);
    ObjectId missingId = new ObjectId();
    when(tenantRepository.findById(missingId)).thenReturn(Optional.empty());

    TenantOnboardRequest request = basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER);
    TenantPartyRelationshipRequest relationshipRequest = new TenantPartyRelationshipRequest();
    relationshipRequest.setToTenantId(missingId.toHexString());
    relationshipRequest.setRelationshipTypeCode(RelationshipTypeEnum.SUB_TENANT);
    request.setPartyRelationships(List.of(relationshipRequest));

    assertThatThrownBy(() -> tenantService.onboardTenant(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missingId.toHexString());

    verify(tenantRepository, never()).save(any());
  }

  @Test
  void includesSmtpAuthCredentialsOnlyWhenSmtpUserIsConfigured() {
    stubTenantSaveAssignsId();
    stubKeycloakAndDownstreamCalls("tenant-public", new ObjectId().toHexString());
    ReflectionTestUtils.setField(tenantService, "smtpUser", "api-key@brevo.com");
    ReflectionTestUtils.setField(tenantService, "smtpPassword", "secret-key");

    tenantService.onboardTenant(basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER));

    ArgumentCaptor<RealmRepresentation> captor = ArgumentCaptor.forClass(RealmRepresentation.class);
    verify(tenantKeycloakService).createRealm(captor.capture());
    assertThat(captor.getValue().getSmtpServer()).containsEntry("user", "api-key@brevo.com");
    assertThat(captor.getValue().getSmtpServer()).containsEntry("password", "secret-key");
  }

  @Test
  void omitsSmtpAuthCredentialKeysEntirelyWhenSmtpUserIsBlank() {
    stubTenantSaveAssignsId();
    stubKeycloakAndDownstreamCalls("tenant-public", new ObjectId().toHexString());

    tenantService.onboardTenant(basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER));

    ArgumentCaptor<RealmRepresentation> captor = ArgumentCaptor.forClass(RealmRepresentation.class);
    verify(tenantKeycloakService).createRealm(captor.capture());
    assertThat(captor.getValue().getSmtpServer()).doesNotContainKey("user");
    assertThat(captor.getValue().getSmtpServer()).doesNotContainKey("password");
  }

  @Test
  void getByIdReturnsTheEntityWhenFound() {
    TenantEntity tenant = TenantEntity.builder().tenantName("Acme").build();
    ObjectId id = new ObjectId();
    tenant.setId(id);
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));

    assertThat(tenantService.getById(id.toHexString()).getTenantName()).isEqualTo("Acme");
  }

  @Test
  void getByIdThrowsWhenNotFound() {
    ObjectId id = new ObjectId();
    when(tenantRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> tenantService.getById(id.toHexString())).isInstanceOf(IllegalArgumentException.class);
  }
}
