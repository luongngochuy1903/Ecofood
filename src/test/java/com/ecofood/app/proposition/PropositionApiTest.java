package com.ecofood.app.proposition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.proposition.controller.PropositionController;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.repository.*;
import com.ecofood.app.proposition.service.*;
import com.ecofood.app.auth.security.InternalJwtRequestFilter;
import com.ecofood.app.auth.service.InternalTokenService;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.core.client.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.service.ACLService;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.data.domain.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class PropositionApiTest {
    private final String owner = new ObjectId().toHexString(),
            enterprise = new ObjectId().toHexString(),
            brand = new ObjectId().toHexString(),
            partner = new ObjectId().toHexString(),
            child = new ObjectId().toHexString(),
            foreign = new ObjectId().toHexString(),
            product = new ObjectId().toHexString(),
            process = new ObjectId().toHexString();
    private static final byte[] KEY = "proposition-api-test-secret-at-least-32-bytes".getBytes();
    private final Map<ObjectId, Document> data = new HashMap<>(), settings = new HashMap<>();
    private MockMvc mvc;
    private MappingMongoConverter converter;
    private CoreProductQueryClient products;
    private PropositionServiceImpl service;
    private String ownerToken, childToken;
    private boolean conflict;

    @BeforeEach
    void setup() throws Exception {
        var conversions = new PropositionMongoConfiguration().propositionMongoConversions();
        var context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        var repo = mock(PropositionRepository.class);
        var currencyRepo = mock(PropositionCurrencySettingsRepository.class);
        var tenants = mock(CoreTenantQueryClient.class);
        products = mock(CoreProductQueryClient.class);
        var enterprises = mock(CoreEnterpriseQueryClient.class);
        var refs =
                List.of(
                        new PropositionTenantReference(
                                owner,
                                "Enterprise",
                                enterprise,
                                PartnerTypeEnum.ENTERPRISE,
                                TenantTypeEnum.MASTER_TENANT,
                                List.of(owner, brand, partner, child),
                                List.of(),
                                false),
                        new PropositionTenantReference(
                                brand,
                                "Brand",
                                null,
                                PartnerTypeEnum.BRAND,
                                TenantTypeEnum.SUB_TENANT,
                                List.of(brand),
                                List.of(new PartyRelationshipResponse(owner, "MT", "Enterprise")),
                                false,
                                "VN"),
                        new PropositionTenantReference(
                                partner,
                                "Distributor",
                                null,
                                PartnerTypeEnum.ECOSYSTEM_PARTNER,
                                TenantTypeEnum.SUB_TENANT,
                                List.of(partner),
                                List.of(new PartyRelationshipResponse(owner, "MT", "Enterprise")),
                                false,
                                "VN"),
                        new PropositionTenantReference(
                                child,
                                "Sub distributor",
                                null,
                                PartnerTypeEnum.ECOSYSTEM_PARTNER,
                                TenantTypeEnum.SUB_TENANT,
                                List.of(child),
                                List.of(new PartyRelationshipResponse(owner, "MT", "Enterprise")),
                                false,
                                "TH"),
                        new PropositionTenantReference(
                                foreign,
                                "Unrelated",
                                null,
                                PartnerTypeEnum.ECOSYSTEM_PARTNER,
                                TenantTypeEnum.SUB_TENANT,
                                List.of(foreign),
                                List.of(),
                                false));
        when(tenants.propositionReferences()).thenReturn(refs);
        when(tenants.propositionReference(anyString()))
                .thenAnswer(
                        i ->
                                refs.stream()
                                        .filter(t -> t.id().equals(i.getArgument(0)))
                                        .findFirst()
                                        .orElseThrow());
        when(tenants.getScopedTenantIds(anyString()))
                .thenAnswer(
                        i ->
                                refs.stream()
                                        .filter(t -> t.id().equals(i.getArgument(0)))
                                        .findFirst()
                                        .orElseThrow()
                                        .scopedTenantIds());
        when(enterprises.getById(enterprise))
                .thenReturn(
                        EnterpriseResponse.builder()
                                .enterpriseId(enterprise)
                                .enterpriseCompanyName("Enterprise")
                                .build());
        var source = source(ProductStatusEnum.APPROVED);
        var productDto =
                new ProductResponse(
                        product,
                        enterprise,
                        "Enterprise",
                        ProductTypeEnum.STANDALONE,
                        1,
                        0L,
                        ProductStatusEnum.APPROVED,
                        "Meal",
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        Instant.now(),
                        null,
                        null,
                        "MEAL",
                        "Meals",
                        null);
        when(products.getApprovedVersion(product, 1)).thenReturn(productDto);
        lenient().when(products.isVersionApproved(eq(product), anyInt())).thenReturn(true);
        when(products.getById(product)).thenReturn(productDto);
        when(products.processes(product)).thenReturn(List.of(source));
        when(products.list(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(productDto)));
        when(repo.findById(any()))
                .thenAnswer(
                        i ->
                                Optional.ofNullable(data.get(i.getArgument(0)))
                                        .map(d -> converter.read(PropositionEntity.class, d)));
        when(repo.findByEnterpriseIdAndDeletedFalse(any()))
                .thenAnswer(
                        i ->
                                rows().stream()
                                        .filter(p -> p.getEnterpriseId().equals(i.getArgument(0)))
                                        .toList());
        when(repo.findByDeletedFalse()).thenAnswer(i -> rows());
        when(repo.findByDefinitionDistributionPartnerIdsInOrDefinitionBrandIdInOrPlansTenantIdsIn(
                        anyList(), anyList(), anyList()))
                .thenAnswer(i -> rows());
        when(repo.save(any()))
                .thenAnswer(
                        i -> {
                            PropositionEntity p = i.getArgument(0);
                            if (conflict) {
                                conflict = false;
                                throw new org.springframework.dao.OptimisticLockingFailureException(
                                        "Concurrent");
                            }
                            p.setRevision(p.getRevision() == null ? 0 : p.getRevision() + 1);
                            p.setUpdatedAt(Instant.now());
                            var doc = new Document();
                            converter.write(p, doc);
                            data.put(p.getId(), doc);
                            return p;
                        });
        when(currencyRepo.findByEnterpriseId(any()))
                .thenAnswer(
                        i ->
                                Optional.ofNullable(settings.get(i.getArgument(0)))
                                        .map(
                                                d ->
                                                        converter.read(
                                                                PropositionCurrencySettings.class,
                                                                d)));
        when(currencyRepo.save(any()))
                .thenAnswer(
                        i -> {
                            PropositionCurrencySettings p = i.getArgument(0);
                            p.setRevision(p.getRevision() == null ? 0 : p.getRevision() + 1);
                            var doc = new Document();
                            converter.write(p, doc);
                            settings.put(p.getEnterpriseId(), doc);
                            return p;
                        });
        var access = new PropositionAccess(tenants, new ACLService(), enterprises);
        var parties = new PropositionParties(tenants);
        var currencies = new PropositionCurrencies(currencyRepo);
        var validation =
                new PropositionValidation(Validation.buildDefaultValidatorFactory().getValidator());
        var processes = new FakeProductProcessClient();
        var userQueryClient = mock(com.ecofood.core.client.CoreUserQueryClient.class);
        var mapper = new PropositionMapper(access, parties, currencies, processes, userQueryClient);
        var sequences = mock(com.ecofood.core.service.SequenceGeneratorService.class);
        when(sequences.generateSequence(any())).thenAnswer(i -> 1L);
        service =
                new PropositionServiceImpl(
                        repo,
                        products,
                        enterprises,
                        processes,
                        access,
                        validation,
                        parties,
                        currencies,
                        mapper,
                        sequences);
        var query =
                new PropositionQueryServiceImpl(
                        repo, access, mapper, parties, currencies, products);
        var tokens = mock(InternalTokenService.class);
        when(tokens.verifyToken(anyString()))
                .thenAnswer(
                        i ->
                                Jwts.parser()
                                        .verifyWith(Keys.hmacShaKeyFor(KEY))
                                        .build()
                                        .parseSignedClaims((String) i.getArgument(0))
                                        .getPayload());
        var filter = new InternalJwtRequestFilter(tokens);
        ReflectionTestUtils.setField(filter, "jwtSecret", Base64.getEncoder().encodeToString(KEY));
        ReflectionTestUtils.setField(filter, "expectedIssuer", "app");
        ReflectionTestUtils.setField(filter, "expectedAudience", "app-api");
        mvc =
                MockMvcBuilders.standaloneSetup(new PropositionController(service, query))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                        .addFilters(filter)
                        .build();
        ownerToken = token(owner, "ENTERPRISE_ADMIN", "ENTERPRISE");
        childToken = token(child, "TENANT_PRODUCT_MANAGER", "TENANT");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        com.ecofood.core.security.AuthenticationContext.unload();
    }

    private ProductProcessResponse source(ProductStatusEnum status) {
        return new ProductProcessResponse(
                process,
                product,
                1,
                0L,
                status,
                "Version 1",
                new ProductProcessElementResponse<>(
                        "definition",
                        "Definition",
                        List.of(
                                new ProductDefinitionAttributeResponse(
                                        "attr", process, 0L, "Meal", "MEAL", "Meals", null, null,
                                        List.of(), null))),
                null,
                null,
                false,
                null,
                null,
                Instant.now(),
                null,
                null);
    }

    private List<PropositionEntity> rows() {
        return data.values().stream().map(d -> converter.read(PropositionEntity.class, d)).toList();
    }

    private String token(String tenant, String role, String group) {
        return Jwts.builder()
                .subject(new ObjectId().toHexString())
                .id(UUID.randomUUID().toString())
                .claim("tenantId", tenant)
                .claim("roles", List.of(role))
                .claim("roleGroups", List.of(group))
                .expiration(new Date(System.currentTimeMillis() + 600000))
                .signWith(Keys.hmacShaKeyFor(KEY))
                .compact();
    }

    private String definition() {
        return """
      {"name":"Distribution","regionIds":["TH","VN"],"brandId":"%s","distributionPartnerIds":["%s","%s"],"targetYear":2027,"expectRevenue":"1000","enterpriseProfitShare":"60","platformProfitShare":"10","distributionProfitShare":"30","allowedOverrides":["NAME","IMAGE_URL"]}
      """
                .formatted(brand, partner, child);
    }

    private String createBody() {
        return "{\"productId\":\""
                + product
                + "\",\"definition\":"
                + definition()
                + "}";
    }

    private String create() throws Exception {
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.currency").value("USD"))
                .andExpect(jsonPath("$.productId").value(product));
        return data.keySet().iterator().next().toHexString();
    }

    private String plan(long revision, String region, String tenant, String pricing) {
        return """
      {"expectedRevision":%s,"productVersion":1,"name":"Plan","category":"Food","regionIds":["%s"],"tenantIds":["%s"],"pricing":%s}
      """
                .formatted(revision, region, tenant, pricing);
    }

    /**
     * A Plan's name/description/category/image/ingredients/recipe/costing now
     * live in their own product_process + attribute rows (see
     * CoreProductProcessClient), not embedded in the Proposition — this is a
     * minimal in-memory stand-in for that store, keyed by family id (a
     * Plan's own id), mirroring exactly what ProductProcessWriteServiceImpl
     * does against real Mongo collections.
     */
    private static final class FakeProductProcessClient extends CoreProductProcessClient {
        private final Map<String, ProductFamilySaveRequest> store = new HashMap<>();

        FakeProductProcessClient() {
            super(null);
        }

        @Override
        public ProductProcessResponse save(String familyId, ProductFamilySaveRequest r) {
            if (r.imageUrl() != null && !r.imageUrl().isBlank()) {
                try {
                    var uri = java.net.URI.create(r.imageUrl());
                    if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                            || uri.getHost() == null)
                        throw new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.BAD_REQUEST, "Image must be an HTTP(S) URL");
                } catch (IllegalArgumentException ex) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST, "Image must be an HTTP(S) URL");
                }
            }
            var currency = r.pricing() != null ? r.pricing().currency() : r.costing() != null ? r.costing().currency() : null;
            if (currency != null && !currency.isBlank()) {
                try {
                    java.util.Currency.getInstance(currency.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST, "Select a valid ISO 4217 currency");
                }
            }
            store.put(familyId, r);
            return get(familyId);
        }

        @Override
        public ProductProcessResponse get(String familyId) {
            var r = store.get(familyId);
            if (r == null)
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Plan content not found");
            var currency =
                    r.pricing() != null && r.pricing().currency() != null
                            ? r.pricing().currency()
                            : r.costing() != null ? r.costing().currency() : null;
            var costing =
                    new ProductCostingAttributeResponse(
                            familyId + "-costing",
                            familyId,
                            0L,
                            r.costing() == null ? null : r.costing().ingredientCost(),
                            r.costing() == null ? null : r.costing().packagingCost(),
                            r.costing() == null ? null : r.costing().laborCost(),
                            r.costing() == null ? null : r.costing().operationCost(),
                            r.costing() == null ? null : r.costing().marketingFee(),
                            r.costing() == null ? null : r.costing().targetMargin(),
                            r.costing() == null ? null : r.costing().tax(),
                            null,
                            null,
                            r.pricing() == null ? null : r.pricing().sellingPrice(),
                            currency,
                            r.pricing() == null ? null : r.pricing().note());
            var definition =
                    new ProductDefinitionAttributeResponse(
                            familyId + "-definition",
                            familyId,
                            0L,
                            r.name(),
                            null,
                            r.category(),
                            r.description(),
                            r.imageUrl(),
                            r.ingredients() == null ? List.of() : r.ingredients(),
                            r.recipe());
            return new ProductProcessResponse(
                    familyId + "-process",
                    familyId,
                    1,
                    0L,
                    ProductStatusEnum.DRAFT,
                    "v1",
                    new ProductProcessElementResponse<>("definition", "Definition", List.of(definition)),
                    new ProductProcessElementResponse<>("costing", "Costing", List.of(costing)),
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    @Test
    void realMvcAndMongoMappingSupportMultiplePlansScopedApprovalAndAtomicAudit() throws Exception {
        String id = create(), path = "/api/v1/propositions/" + id;
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        plan(
                                                0,
                                                "TH",
                                                child,
                                                "{\"sellingPrice\":25.10}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plans[0].costing.sellingPrice").value("25.1"));
        String planId = rows().get(0).getPlans().get(0).getIdAsString();
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(1, "VN", partner, "{\"sellingPrice\":30}")))
                .andExpect(status().isCreated());
        action(path + "/plans/" + planId + "/submit", 200);
        mvc.perform(
                        post(path + "/plans/" + planId + "/approve")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedRevision\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(2))
                .andExpect(jsonPath("$.plans[0].status").value("APPROVED"));
        mvc.perform(
                        put(path + "/plans/" + planId)
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(3, "TH", child, "null")))
                .andExpect(status().isConflict());
        mvc.perform(get(path).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(2));
        assertThat(rows().get(0).getChanges()).hasSize(5);
        assertThat(PlanStatusEnum.values())
                .containsExactly(
                        PlanStatusEnum.DRAFT,
                        PlanStatusEnum.PENDING_REVIEW,
                        PlanStatusEnum.APPROVED,
                        PlanStatusEnum.REJECTED);
    }

    @Test
    void approvingAPlanIsRejectedWhileItsBoundProductVersionIsNotApproved() throws Exception {
        String id = create(), path = "/api/v1/propositions/" + id;
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(0, "TH", child, "{\"sellingPrice\":25.10}")))
                .andExpect(status().isCreated());
        String planId = rows().get(0).getPlans().get(0).getIdAsString();
        action(path + "/plans/" + planId + "/submit", 200);
        when(products.isVersionApproved(product, 1)).thenReturn(false);
        mvc.perform(
                        post(path + "/plans/" + planId + "/approve")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedRevision\":2}"))
                .andExpect(status().isConflict());
        assertThat(rows().get(0).getPlans().get(0).getStatus())
                .isEqualTo(PlanStatusEnum.PENDING_REVIEW);
    }

    @Test
    void planDirectoryListsVisiblePlansAndSupportsSearch() throws Exception {
        String path = "/api/v1/propositions/" + create();
        mvc.perform(post(path + "/plans")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
          {"expectedRevision":0,"productVersion":1,"name":"Alpha Plan","category":"Food","regionIds":["TH"],"tenantIds":["%s"]}
          """.formatted(child)))
                .andExpect(status().isCreated());
        mvc.perform(post(path + "/plans")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
          {"expectedRevision":1,"productVersion":1,"name":"Beta Plan","category":"Food","regionIds":["VN"],"tenantIds":["%s"]}
          """.formatted(partner)))
                .andExpect(status().isCreated());
        mvc.perform(get(path + "/plans/directory").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Alpha Plan"))
                .andExpect(jsonPath("$.content[1].name").value("Beta Plan"));
        mvc.perform(get(path + "/plans/directory").header("Authorization", "Bearer " + ownerToken)
                        .param("search", "beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Beta Plan"));
    }

    @Test
    void planCanUseCurrencyOutsideEnterpriseReportingPolicyThroughApproval() throws Exception {
        String path = "/api/v1/propositions/" + create();
        mvc.perform(get(path).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCurrencies[?(@ == 'THB')]").isNotEmpty());
        mvc.perform(post(path + "/plans")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plan(0, "TH", child, "{\"currency\":\"ZZZ\",\"sellingPrice\":100}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path + "/plans")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plan(0, "TH", child, "{\"currency\":\"THB\",\"sellingPrice\":100.00}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.currency").value("USD"))
                .andExpect(jsonPath("$.plans[0].costing.currency").value("THB"))
                .andExpect(jsonPath("$.plans[0].costing.sellingPrice").value("100.0"));
        String planId = rows().get(0).getPlans().get(0).getIdAsString();
        mvc.perform(post(path + "/plans/" + planId + "/submit")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post(path + "/plans/" + planId + "/approve")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.plans[0].costing.currency").value("THB"));
    }

    @Test
    void currencySettingsOnlyDefaultNewPropositionsAndDoNotRestrictSelections() throws Exception {
        String path = "/api/v1/propositions/" + create();
        mvc.perform(post(path + "/plans").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plan(0, "TH", child, "{\"currency\":\"THB\",\"sellingPrice\":100.00}")))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/propositions/settings")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableCurrencies\":[\"USD\"],\"defaultCurrency\":\"EUR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultCurrency").value("EUR"))
                .andExpect(jsonPath("$.availableCurrencies[?(@ == 'THB')]").isNotEmpty());
        mvc.perform(get(path).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.currency").value("USD"))
                .andExpect(jsonPath("$.plans[0].costing.currency").value("THB"));
        mvc.perform(put(path).header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"definition\":" + definition() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.currency").value("USD"));
        mvc.perform(post("/api/v1/propositions").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.currency").value("EUR"));
        String explicit = createBody().replace("\"name\":\"Distribution\"",
                "\"name\":\"Distribution\",\"currency\":\"VND\"");
        mvc.perform(post("/api/v1/propositions").header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(explicit))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.currency").value("VND"));
    }

    @Test
    void verifiedRolesAndExistingMtRelationshipsControlAllAccess() throws Exception {
        String id = create(), path = "/api/v1/propositions/" + id;
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role :
                List.of("ENTERPRISE_PARTNERSHIP_MANAGER", "ENTERPRISE_DASHBOARD_MANAGER"))
            mvc.perform(
                            post("/api/v1/propositions")
                                    .header(
                                            "Authorization",
                                            "Bearer " + token(owner, role, "ENTERPRISE"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(createBody()))
                    .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + childToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody()))
                .andExpect(status().isForbidden());
        String foreignToken = token(foreign, "TENANT_ADMIN", "TENANT");
        mvc.perform(get(path).header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/propositions").header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + childToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(0, "TH", partner, "null")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/propositions/options")
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brands[0].id").value(brand));
    }

    @Test
    void invalidSourceMarketsRevisionAndFailedCasDoNotPublishChanges() throws Exception {
        String id = create(), path = "/api/v1/propositions/" + id;
        // rows().get(0) is this file's usual shortcut for "the one Proposition
        // this test created" — but this test (uniquely) creates a second one
        // just below, so every later lookup here goes through this instead,
        // scoped to the actual `id` the rest of the test exercises.
        java.util.function.Supplier<PropositionEntity> loaded =
                () ->
                        rows().stream()
                                .filter(p -> p.getIdAsString().equals(id))
                                .findFirst()
                                .orElseThrow();
        when(products.processes(product)).thenReturn(List.of(source(ProductStatusEnum.DRAFT)));
        // Neither the Proposition nor its Plans ever require an APPROVED
        // source Product — creating one with a DRAFT source succeeds below
        // (the only thing this second, otherwise-unused Proposition proves),
        // and every addPlan attempt in the loop next still gets rejected, but
        // each for its own, unrelated reason (never the DRAFT source).
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody()))
                .andExpect(status().isCreated());
        for (String body :
                List.of(
                        plan(0, "OTHER", child, "null"),
                        plan(0, "TH", foreign, "null"),
                        plan(0, "TH", child, "{\"sellingPrice\":-1}")))
            mvc.perform(
                            post(path + "/plans")
                                    .header("Authorization", "Bearer " + ownerToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        // Restore an APPROVED source for the rest of this test — not load-
        // bearing (a Plan never requires one), just more realistic data.
        when(products.processes(product)).thenReturn(List.of(source(ProductStatusEnum.APPROVED)));
        conflict = true;
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(0, "TH", child, "null")))
                .andExpect(status().isConflict());
        assertThat(loaded.get().getChanges()).hasSize(1);
        assertThat(loaded.get().getPlans()).isEmpty();
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(0, "TH", child, "null")))
                .andExpect(status().isCreated());
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(0, "VN", child, "null")))
                .andExpect(status().isConflict());
        // Same country + tenant + (open-ended) period as the Plan just added
        // above — no longer rejected: Plans are no longer required to avoid
        // overlapping each other.
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(1, "TH", child, "null")))
                .andExpect(status().isCreated());
        String planId = loaded.get().getPlans().get(0).getIdAsString();
        mvc.perform(
                        post(path + "/plans/" + planId + "/approve")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedRevision\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        delete(path + "/plans/" + planId)
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(1));
    }

    @Test
    void updateKeepsApprovedProcessPinnedAndCurrencyPolicyIsEnterpriseScoped() throws Exception {
        mvc.perform(
                        put("/api/v1/propositions/settings")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"availableCurrencies\":[\"USD\",\"VND\",\"KWD\"],\"defaultCurrency\":\"USD\"}"))
                .andExpect(status().isOk());
        String id = create(), path = "/api/v1/propositions/" + id;
        // PropositionUpdateRequest has no productId/product-binding field at all —
        // updating a Proposition can never change which Product it points at
        // (that's immutable since create()); an extra, unknown field here is
        // simply ignored, same as before.
        mvc.perform(
                        put(path)
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expectedRevision\":0,\"productProcessId\":\""
                                                + new ObjectId()
                                                + "\",\"definition\":"
                                                + definition()
                                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(product));
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        plan(
                                                1,
                                                "TH",
                                                child,
                                                "{\"currency\":\"KWD\",\"sellingPrice\":1.123}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plans[0].costing.sellingPrice").value("1.123"));
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        plan(
                                                2,
                                                "VN",
                                                child,
                                                "{\"currency\":\"EUR\",\"sellingPrice\":1}")))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/propositions/settings")
                                .header("Authorization", "Bearer " + childToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void quarterlyRevenueRoundTripsAndTotalsAreCalculatedServerSide() throws Exception {
        var body =
                createBody()
                        .replace(
                                "\"expectRevenue\":\"1000\"",
                                "\"expectRevenue\":\"1\",\"expectMetrics\":["
                                        + "{\"quarter\":4,\"year\":2026,\"revenue\":\"999999999999999.99\"},"
                                        + "{\"quarter\":1,\"year\":2027,\"revenue\":\"0.01\"}]");
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.expectRevenue").value("1000000000000000.00"))
                .andExpect(jsonPath("$.definition.expectMetrics[0].quarter").value(4))
                .andExpect(
                        jsonPath("$.definition.expectMetrics[0].revenue")
                                .value("999999999999999.99"));
        mvc.perform(
                        get("/api/v1/propositions/" + rows().get(0).getIdAsString())
                                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.expectMetrics[1].quarter").value(1))
                .andExpect(jsonPath("$.definition.expectMetrics[1].year").value(2027))
                .andExpect(jsonPath("$.definition.expectMetrics[1].revenue").value("0.01"));
    }

    @Test
    void aDistributionPartnerSeesEveryPlanEvenOnesNotListingItDirectly() throws Exception {
        // `partner` is a distributionPartnerId on every createBody() Proposition
        // (see definition()), but every Plan added below is scoped to `child`
        // only — `partner` never appears in any individual Plan's tenantIds.
        String id = create(), path = "/api/v1/propositions/" + id;
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(0, "TH", child, "null")))
                .andExpect(status().isCreated());
        String partnerToken = token(partner, "TENANT_ADMIN", "TENANT");
        mvc.perform(get(path).header("Authorization", "Bearer " + partnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(1));
        mvc.perform(get(path + "/plans/directory").header("Authorization", "Bearer " + partnerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void actualMetricsAreZeroSeededAtCreationMatchingExpectMetricsPeriods() throws Exception {
        var body =
                createBody()
                        .replace(
                                "\"expectRevenue\":\"1000\"",
                                "\"expectRevenue\":\"1\",\"expectMetrics\":["
                                        + "{\"quarter\":4,\"year\":2026,\"revenue\":\"100\"}]");
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actualMetrics[0].quarter").value(4))
                .andExpect(jsonPath("$.actualMetrics[0].year").value(2026))
                .andExpect(jsonPath("$.actualMetrics[0].totalSalesVolume").value("0"))
                .andExpect(jsonPath("$.actualMetrics[0].profit").value("0"))
                .andExpect(jsonPath("$.actualMetrics[0].ingredientCost").value("0"))
                .andExpect(jsonPath("$.actualMetrics[0].platformCost").value("0"))
                .andExpect(jsonPath("$.actualMetrics[0].revenue").value("0"));
    }

    @Test
    void duplicateQuarterlyRevenuePeriodsAreRejected() throws Exception {
        var body =
                createBody()
                        .replace(
                                "\"expectRevenue\":\"1000\"",
                                "\"expectRevenue\":\"1000\",\"expectMetrics\":["
                                        + "{\"quarter\":4,\"year\":2026,\"revenue\":\"1\"},"
                                        + "{\"quarter\":4,\"year\":2026,\"revenue\":\"2\"}]");
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void economicMoneyRoundTripsAsExactDecimalStrings() throws Exception {
        String body =
                createBody()
                        .replace(
                                "\"expectRevenue\":\"1000\"",
                                "\"expectRevenue\":\"999999999999999.99\",\"expectMetrics\":[{\"month\":1,\"year\":2027,\"profit\":\"0.1234567890123456789\"}]");
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.expectRevenue").value("999999999999999.99"))
                .andExpect(
                        jsonPath("$.definition.expectMetrics[0].profit")
                                .value("0.1234567890123456789"));
    }

    @Test
    void brandSubTenantCanDistributeAndCountriesAreDerivedServerSide() throws Exception {
        var body =
                createBody()
                        .replace(partner, brand)
                        .replace("\"regionIds\":[\"TH\",\"VN\"]", "\"regionIds\":[\"FORGED\"]");
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.regionIds[0]").value("TH"))
                .andExpect(jsonPath("$.definition.regionIds[1]").value("VN"));
        var id = data.keySet().iterator().next().toHexString();
        mvc.perform(
                        post("/api/v1/propositions/" + id + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(0, "VN", brand, "{\"sellingPrice\":10}")))
                .andExpect(status().isCreated());
        mvc.perform(
                        post("/api/v1/propositions/" + id + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(1, "TH", brand, "null")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void separateBrandIsOptionalAndPartnerCanReadWithoutManagingPlans() throws Exception {
        var body = createBody().replace("\"brandId\":\"" + brand + "\",", "");
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated());
        String id = data.keySet().iterator().next().toHexString();
        mvc.perform(
                        get("/api/v1/propositions/" + id)
                                .header("Authorization", "Bearer " + childToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManagePlans").value(false));
        mvc.perform(
                        get("/api/v1/propositions/" + id)
                                .header(
                                        "Authorization",
                                        "Bearer " + token(foreign, "TENANT_ADMIN", "TENANT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void planBecomesActiveOnItsOwnApprovalWithoutAnyPropositionApproval() throws Exception {
        String id = create(), path = "/api/v1/propositions/" + id;
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(0, "VN", partner, "{\"sellingPrice\":10}")))
                .andExpect(status().isCreated());
        String planId = rows().get(0).getPlans().get(0).getIdAsString(),
                planPath = path + "/plans/" + planId;
        action(planPath + "/approve", 409);
        action(planPath + "/submit", 200)
                .andExpect(jsonPath("$.plans[0].status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.plans[0].canApprove").value(true))
                .andExpect(jsonPath("$.plans[0].canEdit").value(false));
        mvc.perform(
                        put(planPath)
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(2, "VN", partner, "null")))
                .andExpect(status().isConflict());
        action(planPath + "/approve", 200)
                .andExpect(jsonPath("$.plans[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.plans[0].active").value(true));
        action(planPath + "/submit", 409);
        assertThat(rows().get(0).getChanges())
                .extracting(PropositionChange::action)
                .contains("SUBMIT_PLAN", "APPROVE_PLAN");
    }

    @Test
    void creatingAndApprovingPlanVersionsWorksWithoutAnyPropositionApproval()
            throws Exception {
        String id = create(), path = "/api/v1/propositions/" + id;
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(0, "VN", partner, "{\"sellingPrice\":10}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plans[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.plans[0].active").value(false));
        String planId = rows().get(0).getPlans().get(0).getIdAsString(),
                planPath = path + "/plans/" + planId;
        action(planPath + "/submit", 200).andExpect(jsonPath("$.plans[0].active").value(false));
        action(planPath + "/approve", 200).andExpect(jsonPath("$.plans[0].active").value(true));
        action(planPath + "/versions", 201)
                .andExpect(jsonPath("$.plans[1].version").value(2))
                .andExpect(jsonPath("$.plans[1].status").value("DRAFT"))
                .andExpect(jsonPath("$.plans[0].active").value(true));
        String next = rows().get(0).getPlans().get(1).getIdAsString();
        action(path + "/plans/" + next + "/submit", 200);
        action(path + "/plans/" + next + "/approve", 200)
                .andExpect(jsonPath("$.plans[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.plans[0].active").value(false))
                .andExpect(jsonPath("$.plans[1].active").value(true));
    }

    @Test
    void reviewChecksScopeAndRevisionsOnPlanActions() throws Exception {
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody().replace("\"30\"", "\"20\"")))
                .andExpect(status().isBadRequest());
        String id = create(), path = "/api/v1/propositions/" + id;
        mvc.perform(
                        put(path)
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expectedRevision\":0,\"definition\":"
                                                + definition()
                                                        .replace(
                                                                "\"targetYear\":2027",
                                                                "\"targetYear\":null")
                                                + "}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(plan(1, "VN", partner, "{\"sellingPrice\":10}")))
                .andExpect(status().isCreated());
        String planPath = path + "/plans/" + rows().get(0).getPlans().get(0).getIdAsString();
        mvc.perform(
                        post(planPath + "/submit")
                                .header("Authorization", "Bearer " + childToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedRevision\":2}"))
                .andExpect(status().isForbidden());
        conflict = true;
        action(planPath + "/submit", 409);
        assertThat(rows().get(0).getPlans().get(0).getStatus()).isEqualTo(PlanStatusEnum.DRAFT);
        action(planPath + "/submit", 200);
        action(planPath + "/approve", 200).andExpect(jsonPath("$.plans[0].active").value(true));
        mvc.perform(
                        post(planPath + "/approve")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedRevision\":2}"))
                .andExpect(status().isConflict());
    }

    @Test
    void removedSegmentAndScopeFieldsDoNotAppearInContracts() throws Exception {
        var body =
                createBody()
                        .replace(
                                "\"name\":\"Distribution\"",
                                "\"name\":\"Distribution\",\"allowedSegments\":[\"VIP\"],\"allowedScopes\":[\"ONLINE\"]");
        mvc.perform(
                        post("/api/v1/propositions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.allowedSegments").doesNotExist())
                .andExpect(jsonPath("$.definition.allowedScopes").doesNotExist());
        String path = "/api/v1/propositions/" + rows().get(0).getIdAsString();
        mvc.perform(
                        post(path + "/plans")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        plan(0, "VN", partner, "null")
                                                .replace(
                                                        "\"name\":\"Plan\"",
                                                        "\"name\":\"Plan\",\"segment\":\"VIP\",\"scope\":\"ONLINE\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plans[0].segment").doesNotExist())
                .andExpect(jsonPath("$.plans[0].scope").doesNotExist());
    }

    private ResultActions action(String path, int code) throws Exception {
        return mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expectedRevision\":"
                                                + rows().get(0).getRevision()
                                                + "}"))
                .andExpect(status().is(code));
    }
}
