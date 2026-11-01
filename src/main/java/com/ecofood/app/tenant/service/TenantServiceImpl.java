package com.ecofood.app.tenant.service;

import com.ecofood.core.client.CoreEnterpriseClient;
import com.ecofood.core.client.CoreUserClient;
import com.ecofood.core.client.CoreUserRoleAssignmentClient;
import com.ecofood.core.dto.CommunicationDetailResponse;
import com.ecofood.core.dto.CommunicationResponse;
import com.ecofood.core.dto.ContactResponse;
import com.ecofood.core.dto.DomainAvailabilityResponse;
import com.ecofood.core.dto.EnterpriseCommunicationDetailRequest;
import com.ecofood.core.dto.EnterpriseCommunicationRequest;
import com.ecofood.core.dto.EnterpriseContactRequest;
import com.ecofood.core.dto.EnterpriseCreateRequest;
import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.TenantAssignmentRequest;
import com.ecofood.core.dto.TenantLoginInfoResponse;
import com.ecofood.core.dto.TenantOnboardRequest;
import com.ecofood.core.dto.TenantOnboardResponse;
import com.ecofood.core.dto.TenantPartyRelationshipRequest;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.dto.TenantUpdateRequest;
import com.ecofood.core.dto.UserCreateRequest;
import com.ecofood.core.dto.UserResponse;
import com.ecofood.core.entity.RoleLevel;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import com.ecofood.core.service.SequenceGeneratorService;
import com.ecofood.core.service.TenantService;
import com.ecofood.core.util.FormatCode;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.enterprise.entity.Communication;
import com.ecofood.app.enterprise.entity.CommunicationDetail;
import com.ecofood.app.enterprise.entity.Contact;
import com.ecofood.app.tenant.entity.PartnerType;
import com.ecofood.app.tenant.entity.TenantEntity;
import com.ecofood.app.tenant.repository.TenantRepository;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.enums.UserCategoryEnum;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.core.enums.TenantTypeEnum;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.enums.SslRequired;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
  private static final int MAX_DIRECTORY_PAGE_SIZE = 100;
  private static final Set<String> DIRECTORY_SORTABLE_FIELDS = Set.of("updatedAt", "tenantName");

  private final TenantKeycloakService tenantKeycloakService;
  private final TenantRepository tenantRepository;
  private final CoreUserClient userClient;
  private final SequenceGeneratorService sequenceGeneratorService;
  private final CoreUserRoleAssignmentClient userRoleAssignmentClient;
  private final CoreEnterpriseClient enterpriseClient;
  private final ACLService aclService;

  @Value("${app.tenant.login-theme}")
  private String loginTheme;

  @Value("${app.tenant.redirect-scheme}")
  private String redirectScheme;

  /** Fallback only — used when a tenant is onboarded with no domainUrl at all. */
  @Value("${app.tenant.redirect-uri}")
  private String redirectUri;

  /** Fallback only — used when a tenant is onboarded with no domainUrl at all. */
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
    // ECO is the platform admin tenant — not scoped to any Enterprise/
    // Master/Tenant hierarchy (see PartnerTypeEnum.ECO javadoc). It is meant
    // to be the very first tenant ever onboarded, and exactly one may ever
    // exist: reject onboarding a second one outright rather than silently
    // creating a sibling platform-admin tenant.
    if (request.getPartnerTypeCode() == PartnerTypeEnum.ECO
        && tenantRepository.existsByPartnerTypePartnerTypeCode(PartnerTypeEnum.ECO.name())) {
      throw new IllegalStateException("An ECO tenant already exists — only one may ever be onboarded");
    }

    // Matches DCMP's real split, confirmed by reading Service_Tenant's
    // actual createTenant() directly: the tenant's Mongo id is just a
    // normal auto-generated id (no manual ObjectId() pre-generation, no
    // Keycloak realm touched yet) — DCMP only ever reuses an
    // ALREADY-EXISTING tenant's Mongo id as a Keycloak realm's internal id
    // later, in a separate "promote to master tenant" step, never at
    // creation time. Our own code never depends on that id-equality either
    // — tenants are looked up by the realm NAME (TenantRepository.findAllByRealm),
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
            .tenantType(request.getTenantType() != null ? request.getTenantType() : TenantTypeEnum.STANDALONE)
            .partnerType(
                PartnerType.builder()
                    .partnerTypeCode(request.getPartnerTypeCode().name())
                    .partnerTypeDescription(request.getPartnerTypeDescription())
                    .build())
            .tenantCode(FormatCode.byPaddingZeros(sequence, TENANT_CODE_LENGTH, TENANT_CODE_PREFIX))
            .partyRelationships(buildPartyRelationships(request.getPartyRelationships()))
            .contact(mapContact(request.getContact()))
            .build();
    tenantRepository.save(tenant);

    String tenantId = tenant.getIdAsString();
    String shortId = tenantId.substring(0, 8);

    String realmName;
    String clientId;

    TenantEntity masterTenant = findMasterTenant(tenant);
    if (masterTenant != null) {
      // A sub-tenant never gets its own Keycloak realm or client — it
      // shares its master tenant's. Its own domainUrl is always a PATH
      // suffix on the SAME origin (host:port) as the master's (see
      // TenantOnboardingPage's buildDomainUrl), so the master client's
      // existing wildcard redirect URI ("https://master-domain/*") already
      // covers it; a separate realm+client per sub-tenant would just be
      // sprawl with no isolation benefit, since they share an origin anyway.
      realmName = masterTenant.getRealm();
      clientId = masterTenant.getClientId();
      linkSubTenantOntoMaster(masterTenant, tenant);
    } else {
      realmName = slugify(request.getTenantName()) + "-" + shortId;
      tenantKeycloakService.createRealm(buildRealm(realmName, request.getTenantName()));

      RealmResource realmResource = tenantKeycloakService.realm(realmName);
      clientId = tenantKeycloakService.createClient(realmResource, buildPublicClient(tenantId, request.getDomainUrl()));
      tenantKeycloakService.relaxUserProfile(realmResource);
    }

    tenant.setRealm(realmName);
    tenant.setClientId(clientId);

    // ENTERPRISE/REGIONAL_ENTERPRISE tenants get a companion EnterpriseEntity
    // (the DCMP-Insurer-shaped record) created alongside them — a
    // project-specific rule, not DCMP-sourced (see PartnerTypeEnum javadoc).
    // Every other partnerType leaves tenant.enterpriseId null. A plain
    // ENTERPRISE needs this too, not just REGIONAL_ENTERPRISE: it's the
    // "related enterprise" a later REGIONAL_ENTERPRISE attaches itself to
    // (see resolveRelatedEnterpriseId) — without its own EnterpriseEntity it
    // could never be referenced that way.
    if (request.getPartnerTypeCode() == PartnerTypeEnum.ENTERPRISE
        || request.getPartnerTypeCode() == PartnerTypeEnum.REGIONAL_ENTERPRISE) {
      EnterpriseCreateRequest enterpriseRequest =
          EnterpriseCreateRequest.builder()
              .enterpriseCompanyName(request.getTenantName())
              .partnerTypeCode(request.getPartnerTypeCode())
              .partnerTypeDescription(request.getPartnerTypeDescription())
              .contact(request.getContact())
              .relatedEnterpriseId(resolveRelatedEnterpriseId(request.getRelatedEnterpriseId()))
              .build();
      EnterpriseResponse enterprise = enterpriseClient.createEnterprise(enterpriseRequest);
      tenant.setEnterpriseId(enterprise.getEnterpriseId());
    }

    tenantRepository.save(tenant);

    // Onboarding's first user is always the tenant's own admin/owner —
    // hardcoded here rather than left to UserServiceImpl's default fallback.
    // Same reasoning for userCategoryCode: this is always a partner-side
    // staff/admin user, never an end CUSTOMER, which is what
    // UserServiceImpl.createUser silently defaults to when it's left unset.
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
            .userCategoryCode(UserCategoryEnum.PARTNER)
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
  public TenantResponse getById(String tenantId) {
    TenantEntity tenant =
        tenantRepository
            .findById(new ObjectId(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
    return toResponse(tenant);
  }

  @Override
  public TenantResponse updateTenant(String tenantId, TenantUpdateRequest request) {
    TenantEntity tenant =
        tenantRepository
            .findById(new ObjectId(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

    RoleLevel level = aclService.getMaxLevel();
    boolean unrestricted = level == RoleLevel.SYSTEM;
    if (!unrestricted
        && level != RoleLevel.ENTERPRISE
        && level != RoleLevel.MASTER_TENANT
        && level != RoleLevel.TENANT) {
      throw new ForbiddenException("Caller's role level does not grant access to update tenants");
    }
    if (!unrestricted && !isWithinCallersScope(tenant, AuthenticationContext.getTenantId())) {
      throw new ForbiddenException("Caller is not allowed to update this tenant");
    }

    tenant.setTenantName(request.getTenantName());
    if (request.getContact() != null) {
      tenant.setContact(mapContact(request.getContact()));
    }
    tenantRepository.save(tenant);

    return toResponse(tenant);
  }

  /**
   * Same scope listTenantDirectory's $or query expresses in Mongo, checked
   * in memory against an already-loaded entity: the caller's own tenant, or
   * a tenant with a MASTER_TENANT-coded relation pointing back at the
   * caller (i.e. the caller is this tenant's master).
   */
  private boolean isWithinCallersScope(TenantEntity tenant, ObjectId callerTenantId) {
    if (tenant.getId().equals(callerTenantId)) {
      return true;
    }
    return tenant.getPartyRelationships().stream()
        .anyMatch(
            relation ->
                RelationshipTypeEnum.MASTER_TENANT.getCode().equals(relation.getRelationshipTypeCode())
                    && callerTenantId.equals(relation.getToPartyId()));
  }

  @Override
  public List<TenantResponse> listTenants(TenantTypeEnum tenantType) {
    List<TenantEntity> tenants =
        tenantType != null ? tenantRepository.findByTenantType(tenantType) : tenantRepository.findAll();
    return tenants.stream().map(this::toResponse).collect(Collectors.toList());
  }

  @Override
  public TenantResponse getRootTenant() {
    return toResponse(
        tenantRepository
            .findByPartnerTypePartnerTypeCode(PartnerTypeEnum.ECO.name())
            .orElseThrow(() -> new IllegalArgumentException("No ECO tenant has been onboarded yet")));
  }

  @Override
  public PageResponse<TenantResponse> listTenantDirectory(int page, int size, String sort, String search) {
    RoleLevel level = aclService.getMaxLevel();
    boolean unrestricted = level == RoleLevel.SYSTEM;
    // TENANT-level is allowed too (TENANT_PARTNERSHIP_MANAGER's Tenant
    // sub-tab) — the self+sub-tenant scoping below naturally degenerates to
    // "just itself" for a TENANT caller, since a sub-tenant can never have
    // further sub-tenants of its own (see findMasterTenant's javadoc).
    if (!unrestricted
        && level != RoleLevel.ENTERPRISE
        && level != RoleLevel.MASTER_TENANT
        && level != RoleLevel.TENANT) {
      throw new ForbiddenException("Caller's role level does not grant access to the tenant directory");
    }

    ObjectId callerTenantId = unrestricted ? null : AuthenticationContext.getTenantId();
    Pageable pageable = PageRequest.of(page, Math.min(Math.max(size, 1), MAX_DIRECTORY_PAGE_SIZE), resolveDirectorySort(sort));
    Page<TenantEntity> result = tenantRepository.findDirectory(callerTenantId, unrestricted, search, pageable);

    return PageResponse.<TenantResponse>builder()
        .content(result.getContent().stream().map(this::toResponse).toList())
        .page(result.getNumber())
        .size(result.getSize())
        .totalElements(result.getTotalElements())
        .totalPages(result.getTotalPages())
        .build();
  }

  private Sort resolveDirectorySort(String sort) {
    if (sort == null || sort.isBlank()) {
      return Sort.by(Sort.Direction.DESC, "updatedAt");
    }

    String[] parts = sort.split(",", 2);
    String field = parts[0].trim();
    if (!DIRECTORY_SORTABLE_FIELDS.contains(field)) {
      throw new BadRequestException("Unsupported sort field: " + field);
    }

    Sort.Direction direction =
        parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
    return Sort.by(direction, field);
  }

  private TenantResponse toResponse(TenantEntity tenant) {
    return TenantResponse.builder()
        .id(tenant.getIdAsString())
        .tenantName(tenant.getTenantName())
        .realm(tenant.getRealm())
        .clientId(tenant.getClientId())
        .enterpriseId(tenant.getEnterpriseIdAsString())
        .domainUrl(tenant.getDomainUrl())
        .tenantType(tenant.getTenantType())
        .partnerTypeCode(
            tenant.getPartnerType() != null ? PartnerTypeEnum.valueOf(tenant.getPartnerType().getPartnerTypeCode()) : null)
        .contact(toContactResponse(tenant.getContact()))
        .build();
  }

  /** Every field off Contact/Communication/CommunicationDetail except `preferred` (boolean) — see ContactResponse javadoc. */
  private ContactResponse toContactResponse(Contact contact) {
    if (contact == null) {
      return null;
    }

    List<CommunicationResponse> communications =
        contact.getCommunications() == null
            ? List.of()
            : contact.getCommunications().stream().map(this::toCommunicationResponse).toList();

    return ContactResponse.builder()
        .contactPartyId(contact.getContactPartyId() != null ? contact.getContactPartyId().toHexString() : null)
        .contactPartyName(contact.getContactPartyName())
        .contactPartyLastName(contact.getContactPartyLastName())
        .contactDepartment(contact.getContactDepartment())
        .contactDesignation(contact.getContactDesignation())
        .communications(communications)
        .build();
  }

  private CommunicationResponse toCommunicationResponse(Communication communication) {
    List<CommunicationDetailResponse> details =
        communication.getCommunicationDetails() == null
            ? List.of()
            : communication.getCommunicationDetails().stream().map(this::toCommunicationDetailResponse).toList();

    return CommunicationResponse.builder()
        .communicationChannelName(communication.getCommunicationChannelName())
        .communicationDetails(details)
        .build();
  }

  private CommunicationDetailResponse toCommunicationDetailResponse(CommunicationDetail detail) {
    return CommunicationDetailResponse.builder()
        .locationType(detail.getLocationType())
        .addressLine1(detail.getAddressLine1())
        .addressLine2(detail.getAddressLine2())
        .isoCityCode(detail.getIsoCityCode())
        .isoCountryCode(detail.getIsoCountryCode())
        .postalCode(detail.getPostalCode())
        .internationalTelecomCountryCode(detail.getInternationalTelecomCountryCode())
        .phoneNumber(detail.getPhoneNumber())
        .emailAddress(detail.getEmailAddress())
        .build();
  }

  @Override
  public DomainAvailabilityResponse checkDomainAvailability(String domainUrl) {
    boolean available = tenantRepository.findByDomainUrl(domainUrl).isEmpty();
    return DomainAvailabilityResponse.builder().domainUrl(domainUrl).available(available).build();
  }

  @Override
  public TenantLoginInfoResponse getLoginInfoByDomain(String domain) {
    TenantEntity tenant =
        tenantRepository
            .findByDomainUrl(domain)
            .orElseThrow(() -> new IllegalArgumentException("No tenant found for domain=" + domain));
    return TenantLoginInfoResponse.builder()
        .domain(domain)
        .realm(tenant.getRealm())
        .clientId(tenant.getClientId())
        .tenantId(tenant.getIdAsString())
        .build();
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

  /** Derives redirectUri/webOrigins from the tenant's own domainUrl; falls back to the global defaults. */
  private ClientRepresentation buildPublicClient(String tenantId, String domainUrl) {
    ClientRepresentation client = new ClientRepresentation();
    String clientId = tenantId + "-public";

    String tenantRedirectUri =
        domainUrl != null && !domainUrl.isBlank() ? redirectScheme + "://" + domainUrl + "/*" : redirectUri;
    String tenantWebOrigin =
        domainUrl != null && !domainUrl.isBlank() ? redirectScheme + "://" + domainUrl : webOrigin;

    client.setClientId(clientId);
    client.setName(clientId);
    client.setEnabled(true);
    client.setProtocol("openid-connect");
    client.setPublicClient(true);
    client.setStandardFlowEnabled(true);
    client.setDirectAccessGrantsEnabled(true);
    client.setAuthorizationServicesEnabled(false);
    client.setRedirectUris(List.of(tenantRedirectUri));
    client.setWebOrigins(List.of(tenantWebOrigin));

    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("pkce.code.challenge.method", "S256");
    client.setAttributes(attributes);

    // "email" is a default scope (not optional) — AuthController resolves a
    // logged-in user back to our own record via the token's email claim,
    // not preferred_username, so every issued token must carry it.
    client.setDefaultClientScopes(List.of("web-origins", "acr", "profile", "basic", "email"));
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
   * Resolves the already-persisted tenant this new one is a sub-tenant of,
   * via its own partyRelationships (RelationshipTypeEnum.MASTER_TENANT
   * code) — null for a MASTER_TENANT/STANDALONE tenant, or defensively for
   * a SUB_TENANT with no such relation recorded (falls back to onboardTenant
   * creating its own realm rather than failing outright).
   */
  private TenantEntity findMasterTenant(TenantEntity tenant) {
    if (tenant.getTenantType() != TenantTypeEnum.SUB_TENANT) {
      return null;
    }

    return tenant.getPartyRelationships().stream()
        .filter(relation -> RelationshipTypeEnum.MASTER_TENANT.getCode().equals(relation.getRelationshipTypeCode()))
        .findFirst()
        .map(
            relation ->
                tenantRepository
                    .findById(relation.getToPartyId())
                    .orElseThrow(
                        () -> new IllegalStateException("Master tenant not found: " + relation.getToPartyId())))
        .orElse(null);
  }

  /**
   * The MASTER_TENANT("MT") relation findMasterTenant reads is only ever
   * recorded on the SUB_TENANT's own record ("I belong to master X") — this
   * writes the reciprocal SUB_TENANT("ST") relation ("I have sub-tenant Y")
   * onto the master's own record, so the master's partyRelationships list
   * actually reflects its sub-tenants, not just the other way around.
   */
  private void linkSubTenantOntoMaster(TenantEntity masterTenant, TenantEntity subTenant) {
    masterTenant
        .getPartyRelationships()
        .add(
            PartyToPartyRelationship.builder()
                .toPartyId(subTenant.getId())
                .relationshipTypeCode(RelationshipTypeEnum.SUB_TENANT.getCode())
                .relationshipTypeDecription(
                    String.format(RelationshipTypeEnum.SUB_TENANT.getDescription(), subTenant.getTenantName()))
                .relationshipReferenceName(subTenant.getTenantName())
                .build());
    tenantRepository.save(masterTenant);
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

  /**
   * Translates TenantOnboardRequest.relatedEnterpriseId — always a TENANT
   * id, the same id space as partyRelationships[].toTenantId and
   * masterTenantId, never a raw Enterprise-collection id — into that
   * tenant's own EnterpriseEntity id, which is what
   * EnterpriseCreateRequest.relatedEnterpriseId actually expects. Same
   * lookup-and-extract pattern as buildPartyRelationships. Null/blank input
   * passes through as null (no related enterprise declared).
   */
  private String resolveRelatedEnterpriseId(String relatedTenantId) {
    if (relatedTenantId == null || relatedTenantId.isBlank()) {
      return null;
    }

    TenantEntity relatedTenant =
        tenantRepository
            .findById(new ObjectId(relatedTenantId))
            .orElseThrow(() -> new IllegalArgumentException("Related tenant not found: " + relatedTenantId));

    String enterpriseId = relatedTenant.getEnterpriseIdAsString();
    if (enterpriseId == null) {
      throw new IllegalArgumentException("Related tenant " + relatedTenantId + " has no enterprise of its own");
    }

    return enterpriseId;
  }

  /**
   * Maps the onboarding request's contact onto TenantEntity.contact — every
   * tenant's own copy, independent of the EnterpriseCreateRequest.contact
   * copy EnterpriseServiceImpl maps separately for ENTERPRISE/
   * REGIONAL_ENTERPRISE (see EnterpriseServiceImpl.mapContact).
   */
  private Contact mapContact(EnterpriseContactRequest request) {
    if (request == null) {
      return null;
    }

    List<Communication> communications =
        request.getCommunications() != null
            ? request.getCommunications().stream().map(this::mapCommunication).toList()
            : new ArrayList<>();

    return Contact.builder()
        .contactPartyName(request.getContactPartyName())
        .contactPartyLastName(request.getContactPartyLastName())
        .contactDepartment(request.getContactDepartment())
        .contactDesignation(request.getContactDesignation())
        .communications(communications)
        .build();
  }

  private Communication mapCommunication(EnterpriseCommunicationRequest request) {
    List<CommunicationDetail> details =
        request.getCommunicationDetails() != null
            ? request.getCommunicationDetails().stream().map(this::mapCommunicationDetail).toList()
            : new ArrayList<>();

    return Communication.builder()
        .communicationChannelName(request.getCommunicationChannelName())
        .preferred(request.isPreferred())
        .communicationDetails(details)
        .build();
  }

  private CommunicationDetail mapCommunicationDetail(EnterpriseCommunicationDetailRequest request) {
    return CommunicationDetail.builder()
        .locationType(request.getLocationType())
        .preferred(request.isPreferred())
        .addressLine1(request.getAddressLine1())
        .addressLine2(request.getAddressLine2())
        .isoCityCode(request.getIsoCityCode())
        .isoCountryCode(request.getIsoCountryCode())
        .postalCode(request.getPostalCode())
        .internationalTelecomCountryCode(request.getInternationalTelecomCountryCode())
        .phoneNumber(request.getPhoneNumber())
        .emailAddress(request.getEmailAddress())
        .build();
  }
}
