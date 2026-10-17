package com.ecofood.app.tenant.service;

import com.ecofood.app.common.client.CoreEnterpriseClient;
import com.ecofood.app.common.client.CoreUserClient;
import com.ecofood.app.common.client.CoreUserRoleAssignmentClient;
import com.ecofood.app.common.dto.EnterpriseCreateRequest;
import com.ecofood.app.common.dto.EnterpriseResponse;
import com.ecofood.app.common.dto.TenantAssignmentRequest;
import com.ecofood.app.common.dto.TenantOnboardRequest;
import com.ecofood.app.common.dto.TenantOnboardResponse;
import com.ecofood.app.common.dto.TenantPartyRelationshipRequest;
import com.ecofood.app.common.dto.UserCreateRequest;
import com.ecofood.app.common.dto.UserResponse;
import com.ecofood.app.common.service.SequenceGeneratorService;
import com.ecofood.app.common.service.TenantService;
import com.ecofood.app.common.util.FormatCode;
import com.ecofood.app.tenant.entity.PartnerType;
import com.ecofood.app.tenant.entity.TenantEntity;
import com.ecofood.app.tenant.repository.TenantRepository;
import com.ecofood.app.userACL.entity.PartnerTypeEnum;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.RelationshipTypeEnum;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.enums.SslRequired;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates tenant onboarding: create the Keycloak realm + public
 * client, persist the tenant, then delegate to CoreUserClient.createUser(...)
 * for the first user — the same path POST /api/v1/users uses, so onboarding
 * never duplicates user-creation logic. Calls into userACL exclusively
 * through CoreUserClient/CoreUserRoleAssignmentClient, never that module's
 * own service/repository packages directly.
 */
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

  private static final int TENANT_CODE_LENGTH = 4;
  private static final String TENANT_CODE_PREFIX = "TE";

  private final TenantKeycloakService tenantKeycloakService;
  private final TenantRepository tenantRepository;
  private final CoreUserClient userClient;
  private final SequenceGeneratorService sequenceGeneratorService;
  private final CoreUserRoleAssignmentClient userRoleAssignmentClient;
  private final CoreEnterpriseClient enterpriseClient;

  @Value("${app.tenant.login-theme}")
  private String loginTheme;

  @Value("${app.tenant.redirect-uri}")
  private String redirectUri;

  @Value("${app.tenant.web-origin}")
  private String webOrigin;

  @Value("${app.tenant.smtp-host}")
  private String smtpHost;

  @Value("${app.tenant.smtp-port}")
  private String smtpPort;

  @Value("${app.tenant.smtp-from}")
  private String smtpFrom;

  @Value("${app.tenant.smtp-auth}")
  private String smtpAuth;

  @Value("${app.tenant.smtp-starttls}")
  private String smtpStarttls;

  @Value("${app.tenant.smtp-user}")
  private String smtpUser;

  @Value("${app.tenant.smtp-password}")
  private String smtpPassword;

  @Override
  public TenantOnboardResponse onboardTenant(TenantOnboardRequest request) {
    // Matches DCMP's real split, confirmed by reading Service_Tenant's
    // actual createTenant() directly: the tenant's Mongo id is just a
    // normal auto-generated id (no manual ObjectId() pre-generation, no
    // Keycloak realm touched yet) — DCMP only ever reuses an
    // ALREADY-EXISTING tenant's Mongo id as a Keycloak realm's internal id
    // later, in a separate "promote to master tenant" step, never at
    // creation time. Our own code never depends on that id-equality either
    // — tenants are looked up by the realm NAME (TenantRepository.findByRealm),
    // not by a shared id — so there's no reason to force it here.
    // Matches DCMP's real generateCode() pattern (Service_Tenant TenantService
    // + Service_Core SequenceGeneratorService/FormatCode, confirmed by reading
    // both directly): an atomic, upserting Mongo counter formatted as a
    // zero-padded, prefixed code — TE0001, TE0002, ... (DCMP's own real
    // padding length is 7 digits; this project uses 4 per this feature's
    // own stated example).
    long sequence = sequenceGeneratorService.generateSequence(TenantEntity.SEQUENCE_NAME);

    TenantEntity tenant =
        TenantEntity.builder()
            .tenantName(request.getTenantName())
            .domainUrl(request.getDomainUrl())
            .partnerType(
                PartnerType.builder()
                    .partnerTypeCode(request.getPartnerTypeCode().name())
                    .partnerTypeDescription(request.getPartnerTypeDescription())
                    .build())
            .tenantCode(FormatCode.byPaddingZeros(sequence, TENANT_CODE_LENGTH, TENANT_CODE_PREFIX))
            .partyRelationships(buildPartyRelationships(request.getPartyRelationships()))
            .build();
    tenantRepository.save(tenant);

    String tenantId = tenant.getIdAsString();
    String shortId = tenantId.substring(0, 8);
    String realmName = slugify(request.getTenantName()) + "-" + shortId;

    tenantKeycloakService.createRealm(buildRealm(realmName, request.getTenantName()));

    RealmResource realmResource = tenantKeycloakService.realm(realmName);
    String clientId = tenantKeycloakService.createClient(realmResource, buildPublicClient(tenantId));
    tenantKeycloakService.relaxUserProfile(realmResource);

    tenant.setRealm(realmName);
    tenant.setClientId(clientId);

    // ENTERPRISE/REGIONAL_ENTERPRISE tenants get a companion EnterpriseEntity
    // (the DCMP-Insurer-shaped record) created alongside them — a
    // project-specific rule, not DCMP-sourced (see PartnerTypeEnum javadoc).
    // Every other partnerType leaves tenant.enterpriseId null.
    if (request.getPartnerTypeCode() == PartnerTypeEnum.ENTERPRISE
        || request.getPartnerTypeCode() == PartnerTypeEnum.REGIONAL_ENTERPRISE) {
      EnterpriseCreateRequest enterpriseRequest =
          EnterpriseCreateRequest.builder()
              .enterpriseCompanyName(request.getTenantName())
              .partnerTypeCode(request.getPartnerTypeCode())
              .partnerTypeDescription(request.getPartnerTypeDescription())
              .contact(request.getContact())
              .relatedEnterpriseId(request.getRelatedEnterpriseId())
              .build();
      EnterpriseResponse enterprise = enterpriseClient.createEnterprise(enterpriseRequest);
      tenant.setEnterpriseId(enterprise.getEnterpriseId());
    }

    tenantRepository.save(tenant);

    // Onboarding's first user is always the tenant's own admin/owner —
    // hardcoded here rather than left to UserServiceImpl's default fallback.
    TenantAssignmentRequest assignment =
        TenantAssignmentRequest.builder()
            .tenantId(tenantId)
            .relationshipTypeCode(RelationshipTypeEnum.TENANT)
            .build();

    UserCreateRequest userRequest =
        UserCreateRequest.builder()
            .username(request.getUsername())
            .email(request.getEmail())
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .tenantAssignments(List.of(assignment))
            .build();
    UserResponse user = userClient.createUser(userRequest);

    // Onboarding's own first user always gets an admin-tier role, picked by
    // the new tenant's partnerType — a project-specific rule (not DCMP-
    // sourced, see PartnerTypeEnum/UserRoleAssignmentServiceImpl javadoc).
    userRoleAssignmentClient.assignRoleForOnboarding(
        new ObjectId(user.getUserId()), tenant.getId(), request.getPartnerTypeCode());

    return TenantOnboardResponse.builder()
        .tenantId(tenantId)
        .tenantName(tenant.getTenantName())
        .realm(realmName)
        .clientId(clientId)
        .user(user)
        .build();
  }

  @Override
  public TenantEntity getById(String tenantId) {
    return tenantRepository
        .findById(new ObjectId(tenantId))
        .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
  }

  /** Realm's internal id is left unset — Keycloak auto-generates it, fully decoupled from our own Mongo tenant id (see onboardTenant's comment). */
  private RealmRepresentation buildRealm(String realmName, String displayName) {
    RealmRepresentation realm = new RealmRepresentation();

    realm.setRealm(realmName);
    realm.setDisplayName(displayName);
    realm.setEnabled(true);
    realm.setLoginTheme(loginTheme);

    realm.setBruteForceProtected(true);
    realm.setFailureFactor(4);

    realm.setLoginWithEmailAllowed(true);
    realm.setRegistrationEmailAsUsername(false);
    realm.setRememberMe(true);
    realm.setVerifyEmail(true);
    realm.setResetPasswordAllowed(true);
    realm.setRegistrationAllowed(false);

    realm.setSslRequired(SslRequired.NONE.toString().toLowerCase());
    realm.setEventsEnabled(true);
    realm.setPasswordPolicy("length(8) and notUsername");

    Map<String, String> smtp = new LinkedHashMap<>();
    smtp.put("host", smtpHost);
    smtp.put("port", smtpPort);
    smtp.put("from", smtpFrom);
    smtp.put("fromDisplayName", displayName);
    smtp.put("auth", smtpAuth);
    smtp.put("starttls", smtpStarttls);
    // Required for authenticated SMTP providers (e.g. Brevo's SMTP relay) —
    // left unset (no user/password keys at all) when smtp-user is blank, so
    // an unauthenticated catch-all like mailpit is unaffected.
    if (smtpUser != null && !smtpUser.isBlank()) {
      smtp.put("user", smtpUser);
      smtp.put("password", smtpPassword);
    }
    realm.setSmtpServer(smtp);

    return realm;
  }

  private ClientRepresentation buildPublicClient(String tenantId) {
    ClientRepresentation client = new ClientRepresentation();
    String clientId = tenantId + "-public";

    client.setClientId(clientId);
    client.setName(clientId);
    client.setEnabled(true);
    client.setProtocol("openid-connect");
    client.setPublicClient(true);
    client.setStandardFlowEnabled(true);
    client.setDirectAccessGrantsEnabled(true);
    client.setAuthorizationServicesEnabled(false);
    client.setRedirectUris(List.of(redirectUri));
    client.setWebOrigins(List.of(webOrigin));

    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("pkce.code.challenge.method", "S256");
    client.setAttributes(attributes);

    client.setDefaultClientScopes(List.of("web-origins", "acr", "profile", "basic"));
    client.setOptionalClientScopes(List.of("offline_access", "address", "phone", "microprofile-jwt"));

    client.setProtocolMappers(List.of(buildTenantIdMapper()));

    return client;
  }

  private ProtocolMapperRepresentation buildTenantIdMapper() {
    ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
    mapper.setName("tenantId");
    mapper.setProtocol("openid-connect");
    mapper.setProtocolMapper("oidc-usermodel-attribute-mapper");

    Map<String, String> config = new LinkedHashMap<>();
    config.put("user.attribute", "tenantId");
    config.put("claim.name", "tenantId");
    config.put("jsonType.label", "String");
    config.put("id.token.claim", "true");
    config.put("access.token.claim", "true");
    config.put("userinfo.token.claim", "true");
    mapper.setConfig(config);

    return mapper;
  }

  private String slugify(String input) {
    return input.toLowerCase().trim().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
  }

  /**
   * Resolves each requested tenant-to-tenant relationship against an
   * already-existing tenant and builds the matching PartyToPartyRelationship
   * — the same generic relation class UserEntity uses, discriminated here by
   * RelationshipTypeEnum's MASTER_TENANT/SUB_TENANT/BELONGTO/OWN codes.
   */
  private List<PartyToPartyRelationship> buildPartyRelationships(
      List<TenantPartyRelationshipRequest> relationshipRequests) {
    List<PartyToPartyRelationship> relations = new ArrayList<>();
    if (relationshipRequests == null) {
      return relations;
    }

    for (TenantPartyRelationshipRequest relationshipRequest : relationshipRequests) {
      TenantEntity relatedTenant =
          tenantRepository
              .findById(new ObjectId(relationshipRequest.getToTenantId()))
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Related tenant not found: " + relationshipRequest.getToTenantId()));

      RelationshipTypeEnum relationshipType = relationshipRequest.getRelationshipTypeCode();

      relations.add(
          PartyToPartyRelationship.builder()
              .toPartyId(relatedTenant.getId())
              .relationshipTypeCode(relationshipType.getCode())
              .relationshipTypeDecription(
                  String.format(relationshipType.getDescription(), relatedTenant.getTenantName()))
              .relationshipReferenceName(relatedTenant.getTenantName())
              .build());
    }

    return relations;
  }
}
