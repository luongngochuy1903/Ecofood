package com.ecofood.app.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.core.client.CoreEnterpriseClient;
import com.ecofood.core.client.CoreUserClient;
import com.ecofood.core.client.CoreUserRoleAssignmentClient;
import com.ecofood.core.dto.CommunicationDetailResponse;
import com.ecofood.core.dto.CommunicationResponse;
import com.ecofood.core.dto.EnterpriseContactRequest;
import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.TenantOnboardRequest;
import com.ecofood.core.dto.TenantOnboardResponse;
import com.ecofood.core.dto.TenantPartyRelationshipRequest;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.dto.TenantUpdateRequest;
import com.ecofood.core.dto.UserResponse;
import com.ecofood.core.entity.RoleLevel;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import com.ecofood.core.service.SequenceGeneratorService;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.enterprise.entity.Communication;
import com.ecofood.app.enterprise.entity.CommunicationDetail;
import com.ecofood.app.enterprise.entity.Contact;
import com.ecofood.app.tenant.entity.TenantEntity;
import com.ecofood.app.tenant.repository.TenantRepository;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.core.enums.TenantTypeEnum;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantServiceImplTest {

  // 32+ raw bytes, base64-encoded — matches AuthenticationContext's documented
  // >=32-byte HS256 requirement (see UserControllerTest).
  private static final String SECRET =
      java.util.Base64.getEncoder().encodeToString("unit-test-only-hmac-secret-0123456789".getBytes());

  @Mock private TenantKeycloakService tenantKeycloakService;
  @Mock private TenantRepository tenantRepository;
  @Mock private CoreUserClient userClient;
  @Mock private SequenceGeneratorService sequenceGeneratorService;
  @Mock private CoreUserRoleAssignmentClient userRoleAssignmentClient;
  @Mock private CoreEnterpriseClient enterpriseClient;
  @Mock private ACLService aclService;
  @Mock private RealmResource realmResource;

  private TenantServiceImpl tenantService;

  @BeforeEach
  void setUp() {
    tenantService =
        new TenantServiceImpl(
            tenantKeycloakService, tenantRepository, userClient, sequenceGeneratorService, userRoleAssignmentClient,
            enterpriseClient, aclService);
    // @Value fields — never populated outside a Spring context, so set
    // directly for this plain Mockito-based unit test.
    ReflectionTestUtils.setField(tenantService, "loginTheme", "app");
    ReflectionTestUtils.setField(tenantService, "redirectScheme", "https");
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

  @AfterEach
  void unloadAuthenticationContext() {
    AuthenticationContext.unload();
  }

  /** listTenantDirectory reads the caller's tenantId off AuthenticationContext, same as a real request would. */
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
  void buildsTheKeycloakClientsRedirectUriFromTheTenantsOwnDomainUrl() {
    stubTenantSaveAssignsId();
    stubKeycloakAndDownstreamCalls("tenant-public", new ObjectId().toHexString());

    tenantService.onboardTenant(basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER));

    ArgumentCaptor<org.keycloak.representations.idm.ClientRepresentation> captor =
        ArgumentCaptor.forClass(org.keycloak.representations.idm.ClientRepresentation.class);
    verify(tenantKeycloakService).createClient(eq(realmResource), captor.capture());
    assertThat(captor.getValue().getRedirectUris()).containsExactly("https://acmefoods.example.com/*");
    assertThat(captor.getValue().getWebOrigins()).containsExactly("https://acmefoods.example.com");
  }

  @Test
  void fallsBackToTheGlobalRedirectUriWhenTenantHasNoDomainUrl() {
    stubTenantSaveAssignsId();
    stubKeycloakAndDownstreamCalls("tenant-public", new ObjectId().toHexString());
    TenantOnboardRequest request = basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER);
    request.setDomainUrl(null);

    tenantService.onboardTenant(request);

    ArgumentCaptor<org.keycloak.representations.idm.ClientRepresentation> captor =
        ArgumentCaptor.forClass(org.keycloak.representations.idm.ClientRepresentation.class);
    verify(tenantKeycloakService).createClient(eq(realmResource), captor.capture());
    assertThat(captor.getValue().getRedirectUris()).containsExactly("http://localhost:3000/*");
    assertThat(captor.getValue().getWebOrigins()).containsExactly("http://localhost:3000");
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
  void subTenantReusesItsMasterTenantsRealmAndClientInsteadOfCreatingItsOwn() {
    when(sequenceGeneratorService.generateSequence(TenantEntity.SEQUENCE_NAME)).thenReturn(1L);
    when(userClient.createUser(any())).thenReturn(UserResponse.builder().userId(new ObjectId().toHexString()).build());
    when(tenantRepository.save(any(TenantEntity.class)))
        .thenAnswer(
            invocation -> {
              TenantEntity tenant = invocation.getArgument(0);
              if (tenant.getId() == null) {
                tenant.setId(new ObjectId());
              }
              return tenant;
            });

    TenantEntity masterTenant = TenantEntity.builder().tenantName("Gamma Hub").build();
    ObjectId masterId = new ObjectId();
    masterTenant.setId(masterId);
    masterTenant.setRealm("gamma-hub-6adc0000");
    masterTenant.setClientId("6adc0000-public");
    when(tenantRepository.findById(masterId)).thenReturn(Optional.of(masterTenant));

    TenantOnboardRequest request = basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER);
    request.setTenantType(TenantTypeEnum.SUB_TENANT);
    TenantPartyRelationshipRequest relationshipRequest = new TenantPartyRelationshipRequest();
    relationshipRequest.setToTenantId(masterId.toHexString());
    relationshipRequest.setRelationshipTypeCode(RelationshipTypeEnum.MASTER_TENANT);
    request.setPartyRelationships(List.of(relationshipRequest));

    TenantOnboardResponse response = tenantService.onboardTenant(request);

    verify(tenantKeycloakService, never()).createRealm(any());
    verify(tenantKeycloakService, never()).createClient(any(), any());
    assertThat(response.getRealm()).isEqualTo("gamma-hub-6adc0000");
    assertThat(response.getClientId()).isEqualTo("6adc0000-public");
  }

  @Test
  void onboardingASubTenantRecordsTheReciprocalSubTenantRelationOnTheMaster() {
    when(sequenceGeneratorService.generateSequence(TenantEntity.SEQUENCE_NAME)).thenReturn(1L);
    when(userClient.createUser(any())).thenReturn(UserResponse.builder().userId(new ObjectId().toHexString()).build());
    when(tenantRepository.save(any(TenantEntity.class)))
        .thenAnswer(
            invocation -> {
              TenantEntity tenant = invocation.getArgument(0);
              if (tenant.getId() == null) {
                tenant.setId(new ObjectId());
              }
              return tenant;
            });

    TenantEntity masterTenant = TenantEntity.builder().tenantName("Gamma Hub").build();
    ObjectId masterId = new ObjectId();
    masterTenant.setId(masterId);
    masterTenant.setRealm("gamma-hub-6adc0000");
    masterTenant.setClientId("6adc0000-public");
    when(tenantRepository.findById(masterId)).thenReturn(Optional.of(masterTenant));

    TenantOnboardRequest request = basicRequest(PartnerTypeEnum.ECOSYSTEM_PARTNER);
    request.setTenantType(TenantTypeEnum.SUB_TENANT);
    TenantPartyRelationshipRequest relationshipRequest = new TenantPartyRelationshipRequest();
    relationshipRequest.setToTenantId(masterId.toHexString());
    relationshipRequest.setRelationshipTypeCode(RelationshipTypeEnum.MASTER_TENANT);
    request.setPartyRelationships(List.of(relationshipRequest));

    tenantService.onboardTenant(request);

    assertThat(masterTenant.getPartyRelationships()).hasSize(1);
    var reciprocalRelation = masterTenant.getPartyRelationships().get(0);
    assertThat(reciprocalRelation.getRelationshipTypeCode()).isEqualTo(RelationshipTypeEnum.SUB_TENANT.getCode());
    assertThat(reciprocalRelation.getToPartyId()).isNotNull();
    verify(tenantRepository).save(masterTenant);
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

  @Test
  void getByIdMapsEveryNonBooleanContactFieldButOmitsPreferred() {
    Communication communication =
        Communication.builder()
            .communicationChannelName("office")
            .preferred(true)
            .communicationDetails(
                List.of(
                    CommunicationDetail.builder()
                        .locationType("HQ")
                        .preferred(true)
                        .addressLine1("123 Main St")
                        .addressLine2("Suite 4")
                        .isoCityCode("HCM")
                        .isoCountryCode("VN")
                        .postalCode("70000")
                        .internationalTelecomCountryCode("+84")
                        .phoneNumber("123456789")
                        .emailAddress("contact@acme.test")
                        .build()))
            .build();
    Contact contact =
        Contact.builder()
            .contactPartyId(new ObjectId())
            .contactPartyName("Jane")
            .contactPartyLastName("Doe")
            .contactDepartment("Sales")
            .contactDesignation("Manager")
            .communications(List.of(communication))
            .build();
    TenantEntity tenant = TenantEntity.builder().tenantName("Acme").contact(contact).build();
    ObjectId id = new ObjectId();
    tenant.setId(id);
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));

    TenantResponse response = tenantService.getById(id.toHexString());

    assertThat(response.getContact().getContactPartyName()).isEqualTo("Jane");
    assertThat(response.getContact().getContactPartyLastName()).isEqualTo("Doe");
    assertThat(response.getContact().getContactDepartment()).isEqualTo("Sales");
    assertThat(response.getContact().getContactDesignation()).isEqualTo("Manager");

    CommunicationResponse communicationResponse = response.getContact().getCommunications().get(0);
    assertThat(communicationResponse.getCommunicationChannelName()).isEqualTo("office");

    CommunicationDetailResponse detailResponse = communicationResponse.getCommunicationDetails().get(0);
    assertThat(detailResponse.getLocationType()).isEqualTo("HQ");
    assertThat(detailResponse.getAddressLine1()).isEqualTo("123 Main St");
    assertThat(detailResponse.getAddressLine2()).isEqualTo("Suite 4");
    assertThat(detailResponse.getIsoCityCode()).isEqualTo("HCM");
    assertThat(detailResponse.getIsoCountryCode()).isEqualTo("VN");
    assertThat(detailResponse.getPostalCode()).isEqualTo("70000");
    assertThat(detailResponse.getInternationalTelecomCountryCode()).isEqualTo("+84");
    assertThat(detailResponse.getPhoneNumber()).isEqualTo("123456789");
    assertThat(detailResponse.getEmailAddress()).isEqualTo("contact@acme.test");
  }

  @Test
  void getByIdReturnsNullContactWhenTenantHasNone() {
    TenantEntity tenant = TenantEntity.builder().tenantName("Acme").build();
    ObjectId id = new ObjectId();
    tenant.setId(id);
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));

    assertThat(tenantService.getById(id.toHexString()).getContact()).isNull();
  }

  private TenantUpdateRequest updateRequest(String tenantName) {
    TenantUpdateRequest request = new TenantUpdateRequest();
    request.setTenantName(tenantName);
    return request;
  }

  @Test
  void updateTenantThrowsWhenTenantNotFound() {
    ObjectId id = new ObjectId();
    when(tenantRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> tenantService.updateTenant(id.toHexString(), updateRequest("New Name")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateTenantSystemLevelCanUpdateAnyTenant() {
    ObjectId id = new ObjectId();
    TenantEntity tenant = TenantEntity.builder().tenantName("Old Name").build();
    tenant.setId(id);
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);

    TenantResponse response = tenantService.updateTenant(id.toHexString(), updateRequest("New Name"));

    assertThat(response.getTenantName()).isEqualTo("New Name");
    verify(tenantRepository).save(tenant);
  }

  @Test
  void updateTenantEnterpriseLevelCanUpdateItsOwnTenant() {
    ObjectId id = new ObjectId();
    TenantEntity tenant = TenantEntity.builder().tenantName("Old Name").build();
    tenant.setId(id);
    authenticateWithTenantId(id.toHexString());
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.ENTERPRISE);

    TenantResponse response = tenantService.updateTenant(id.toHexString(), updateRequest("New Name"));

    assertThat(response.getTenantName()).isEqualTo("New Name");
  }

  @Test
  void updateTenantEnterpriseLevelCanUpdateItsOwnSubTenant() {
    ObjectId callerId = new ObjectId();
    ObjectId subTenantId = new ObjectId();
    TenantEntity subTenant =
        TenantEntity.builder()
            .tenantName("Old Name")
            .partyRelationships(
                List.of(
                    PartyToPartyRelationship.builder()
                        .relationshipTypeCode(RelationshipTypeEnum.MASTER_TENANT.getCode())
                        .toPartyId(callerId)
                        .build()))
            .build();
    subTenant.setId(subTenantId);
    authenticateWithTenantId(callerId.toHexString());
    when(tenantRepository.findById(subTenantId)).thenReturn(Optional.of(subTenant));
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.MASTER_TENANT);

    TenantResponse response = tenantService.updateTenant(subTenantId.toHexString(), updateRequest("New Name"));

    assertThat(response.getTenantName()).isEqualTo("New Name");
  }

  @Test
  void updateTenantEnterpriseLevelCannotUpdateAnUnrelatedTenant() {
    ObjectId callerId = new ObjectId();
    ObjectId otherTenantId = new ObjectId();
    TenantEntity otherTenant = TenantEntity.builder().tenantName("Old Name").build();
    otherTenant.setId(otherTenantId);
    authenticateWithTenantId(callerId.toHexString());
    when(tenantRepository.findById(otherTenantId)).thenReturn(Optional.of(otherTenant));
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.ENTERPRISE);

    assertThatThrownBy(
            () -> tenantService.updateTenant(otherTenantId.toHexString(), updateRequest("New Name")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void updateTenantTenantLevelCanUpdateItsOwnTenant() {
    ObjectId id = new ObjectId();
    TenantEntity tenant = TenantEntity.builder().tenantName("Old Name").build();
    tenant.setId(id);
    authenticateWithTenantId(id.toHexString());
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.TENANT);

    TenantResponse response = tenantService.updateTenant(id.toHexString(), updateRequest("New Name"));

    assertThat(response.getTenantName()).isEqualTo("New Name");
  }

  @Test
  void updateTenantTenantLevelCannotUpdateAnotherTenant() {
    ObjectId callerId = new ObjectId();
    ObjectId otherTenantId = new ObjectId();
    TenantEntity otherTenant = TenantEntity.builder().tenantName("Old Name").build();
    otherTenant.setId(otherTenantId);
    authenticateWithTenantId(callerId.toHexString());
    when(tenantRepository.findById(otherTenantId)).thenReturn(Optional.of(otherTenant));
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.TENANT);

    assertThatThrownBy(
            () -> tenantService.updateTenant(otherTenantId.toHexString(), updateRequest("New Name")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void updateTenantRejectsCustomerLevelCallers() {
    ObjectId id = new ObjectId();
    TenantEntity tenant = TenantEntity.builder().tenantName("Old Name").build();
    tenant.setId(id);
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.CUSTOMER);

    assertThatThrownBy(() -> tenantService.updateTenant(id.toHexString(), updateRequest("New Name")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void updateTenantLeavesContactUntouchedWhenRequestOmitsIt() {
    ObjectId id = new ObjectId();
    Contact existingContact = Contact.builder().contactPartyName("Jane").build();
    TenantEntity tenant = TenantEntity.builder().tenantName("Old Name").contact(existingContact).build();
    tenant.setId(id);
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);

    TenantResponse response = tenantService.updateTenant(id.toHexString(), updateRequest("New Name"));

    assertThat(response.getContact().getContactPartyName()).isEqualTo("Jane");
  }

  @Test
  void updateTenantReplacesContactWhenRequestProvidesOne() {
    ObjectId id = new ObjectId();
    TenantEntity tenant =
        TenantEntity.builder()
            .tenantName("Old Name")
            .contact(Contact.builder().contactPartyName("Old Contact").build())
            .build();
    tenant.setId(id);
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);

    EnterpriseContactRequest contactRequest = new EnterpriseContactRequest();
    contactRequest.setContactPartyName("New Contact");
    contactRequest.setContactPartyLastName("Doe");
    TenantUpdateRequest request = updateRequest("New Name");
    request.setContact(contactRequest);

    TenantResponse response = tenantService.updateTenant(id.toHexString(), request);

    assertThat(response.getContact().getContactPartyName()).isEqualTo("New Contact");
    assertThat(response.getContact().getContactPartyLastName()).isEqualTo("Doe");
  }

  @Test
  void getLoginInfoByDomainReturnsRealmAndClientIdWhenFound() {
    TenantEntity tenant = TenantEntity.builder().tenantName("Acme").realm("acme-realm").clientId("acme-client").build();
    ObjectId id = new ObjectId();
    tenant.setId(id);
    when(tenantRepository.findByDomainUrl("acme.ecofood.local")).thenReturn(Optional.of(tenant));

    var result = tenantService.getLoginInfoByDomain("acme.ecofood.local");

    assertThat(result.getDomain()).isEqualTo("acme.ecofood.local");
    assertThat(result.getRealm()).isEqualTo("acme-realm");
    assertThat(result.getClientId()).isEqualTo("acme-client");
    assertThat(result.getTenantId()).isEqualTo(id.toHexString());
  }

  @Test
  void getLoginInfoByDomainThrowsWhenNotFound() {
    when(tenantRepository.findByDomainUrl("missing.ecofood.local")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> tenantService.getLoginInfoByDomain("missing.ecofood.local"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing.ecofood.local");
  }

  @Test
  void listTenantDirectoryIsUnrestrictedForSystemLevelCallers() {
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    TenantEntity tenant = TenantEntity.builder().tenantName("Acme Foods").build();
    tenant.setId(new ObjectId());
    when(tenantRepository.findDirectory(isNull(), eq(true), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(tenant), PageRequest.of(0, 20), 1));

    PageResponse<TenantResponse> result = tenantService.listTenantDirectory(0, 20, null, null);

    assertThat(result.getContent()).extracting("tenantName").containsExactly("Acme Foods");
    assertThat(result.getTotalElements()).isEqualTo(1);
    // SYSTEM-level callers never need their own tenantId resolved — asserts
    // listTenantDirectory doesn't call AuthenticationContext.getTenantId()
    // when unrestricted, which would throw since no token was set up here.
  }

  @Test
  void listTenantDirectoryScopesToCallersOwnTenantForEnterpriseLevelCallers() {
    String callerTenantId = new ObjectId().toHexString();
    authenticateWithTenantId(callerTenantId);
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.ENTERPRISE);
    when(tenantRepository.findDirectory(eq(new ObjectId(callerTenantId)), eq(false), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    tenantService.listTenantDirectory(0, 20, null, null);

    verify(tenantRepository)
        .findDirectory(
            new ObjectId(callerTenantId), false, null,
            PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "updatedAt")));
  }

  @Test
  void listTenantDirectoryScopesToCallersOwnTenantForMasterTenantLevelCallers() {
    String callerTenantId = new ObjectId().toHexString();
    authenticateWithTenantId(callerTenantId);
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.MASTER_TENANT);
    when(tenantRepository.findDirectory(eq(new ObjectId(callerTenantId)), eq(false), eq("acme"), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    tenantService.listTenantDirectory(0, 20, null, "acme");

    verify(tenantRepository)
        .findDirectory(
            new ObjectId(callerTenantId), false, "acme",
            PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "updatedAt")));
  }

  @Test
  void listTenantDirectoryScopesToCallersOwnTenantForTenantLevelCallers() {
    String callerTenantId = new ObjectId().toHexString();
    authenticateWithTenantId(callerTenantId);
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.TENANT);
    when(tenantRepository.findDirectory(eq(new ObjectId(callerTenantId)), eq(false), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    tenantService.listTenantDirectory(0, 20, null, null);

    verify(tenantRepository)
        .findDirectory(
            new ObjectId(callerTenantId), false, null,
            PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "updatedAt")));
  }

  @Test
  void listTenantDirectoryRejectsCustomerLevelCallers() {
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.CUSTOMER);

    assertThatThrownBy(() -> tenantService.listTenantDirectory(0, 20, null, null))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void listTenantDirectoryRejectsCallersWithNoRoleLevel() {
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.NONE);

    assertThatThrownBy(() -> tenantService.listTenantDirectory(0, 20, null, null))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void listTenantDirectoryDefaultsSortToUpdatedAtDescending() {
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(tenantRepository.findDirectory(isNull(), eq(true), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    tenantService.listTenantDirectory(0, 20, null, null);

    verify(tenantRepository)
        .findDirectory(
            null, true, null, PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "updatedAt")));
  }

  @Test
  void listTenantDirectoryHonorsAnExplicitAscendingSort() {
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(tenantRepository.findDirectory(isNull(), eq(true), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    tenantService.listTenantDirectory(0, 20, "tenantName,asc", null);

    verify(tenantRepository)
        .findDirectory(
            null, true, null, PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.ASC, "tenantName")));
  }

  @Test
  void listTenantDirectoryRejectsAnUnsupportedSortField() {
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);

    assertThatThrownBy(() -> tenantService.listTenantDirectory(0, 20, "clientId,asc", null))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void listTenantDirectoryClampsPageSizeToTheMaximum() {
    when(aclService.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(tenantRepository.findDirectory(isNull(), eq(true), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

    tenantService.listTenantDirectory(0, 500, null, null);

    verify(tenantRepository).findDirectory(isNull(), eq(true), isNull(), eq(PageRequest.of(0, 100,
        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "updatedAt"))));
  }
}
