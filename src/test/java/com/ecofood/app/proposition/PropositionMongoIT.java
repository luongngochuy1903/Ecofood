package com.ecofood.app.proposition;

import static org.assertj.core.api.Assertions.*;
import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import com.ecofood.app.enterprise.entity.Contact;
import com.ecofood.app.enterprise.entity.Communication;
import com.ecofood.app.enterprise.entity.CommunicationDetail;
import com.ecofood.app.tenant.entity.*;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.core.enums.TenantTypeEnum;
import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in integration: real Spring HTTP/JWT/Core delegates/repositories, always a new isolated database. */
@EnabledIfEnvironmentVariable(named = "PROPOSITION_TEST_MONGO_URI", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.org.mongodb.driver=OFF")
class PropositionMongoIT {
    private static final String DATABASE =
            "ecofood_proposition_it_"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final byte[] KEY =
            "isolated-proposition-test-key-never-for-real-users".getBytes();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.data.mongodb.uri",
                () -> {
                    String uri = System.getenv("PROPOSITION_TEST_MONGO_URI");
                    return uri
                            + (uri.contains("?") ? "&" : "?")
                            + "serverSelectionTimeoutMS=10000&connectTimeoutMS=10000";
                });
        registry.add("spring.data.mongodb.database", () -> DATABASE);
        registry.add("jwt.secret", () -> Base64.getEncoder().encodeToString(KEY));
    }

    @LocalServerPort int port;
    @Autowired MongoTemplate mongo;
    @Autowired PropositionRepository propositions;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private String token;

    @AfterEach
    void cleanup() {
        assertThat(mongo.getDb().getName()).isEqualTo(DATABASE);
        mongo.getDb().drop();
    }

    @Test
    void productToPropositionPlanApprovalAndOptionalBrowserUseRealMongo() throws Exception {
        var enterprise = EnterpriseEntity.builder().partyName("Integration Enterprise").build();
        mongo.save(enterprise);
        var owner = tenant("Enterprise", "ENTERPRISE", TenantTypeEnum.MASTER_TENANT);
        owner.setEnterpriseId(enterprise.getIdAsString());
        mongo.save(owner);
        var brand = tenant("Integration Brand", "BRAND", TenantTypeEnum.SUB_TENANT);
        brand.setPartyRelationships(List.of(relation(owner.getId())));
        mongo.save(brand);
        var partner =
                tenant("Integration Distributor", "ECOSYSTEM_PARTNER", TenantTypeEnum.SUB_TENANT);
        partner.setPartyRelationships(List.of(relation(owner.getId())));
        mongo.save(partner);
        token =
                Jwts.builder()
                        .subject(new ObjectId().toHexString())
                        .id(UUID.randomUUID().toString())
                        .claim("tokenType", "access")
                        .claim("tenantId", owner.getIdAsString())
                        .claim("roles", List.of("ENTERPRISE_ADMIN"))
                        .claim("roleGroups", List.of("ENTERPRISE"))
                        .expiration(Date.from(Instant.now().plusSeconds(600)))
                        .signWith(Keys.hmacShaKeyFor(KEY))
                        .compact();
        var product =
                call(
                        "POST",
                        "/api/v1/products",
                        Map.of(
                                "type",
                                "STANDALONE",
                                "name",
                                "Approved integration meal",
                                "code",
                                "IT-001",
                                "category",
                                "Meals",
                                "pricing",
                                Map.of("sellingPrice", 100000, "currency", "VND")),
                        201);
        String productId = product.path("id").asText();
        call("POST", "/api/v1/products/" + productId + "/submit", null, 200);
        call("POST", "/api/v1/products/" + productId + "/approve", null, 200);
        var source =
                call("GET", "/api/v1/propositions/approved-processes", null, 200)
                        .path("content")
                        .get(0);
        var definition =
                Map.of(
                        "name",
                        "Real HTTP Proposition",
                        "brandId",
                        brand.getIdAsString(),
                        "distributionPartnerIds",
                        List.of(partner.getIdAsString(), brand.getIdAsString()),
                        "targetYear",
                        2027,
                        "expectRevenue",
                        "1000",
                        "enterpriseProfitShare",
                        "60",
                        "platformProfitShare",
                        "10",
                        "distributionProfitShare",
                        "30");
        var p =
                call(
                        "POST",
                        "/api/v1/propositions",
                        Map.of(
                                "productId",
                                productId,
                                "productVersion",
                                1,
                                "productProcessId",
                                source.path("productProcessId").asText(),
                                "definition",
                                definition),
                        201);
        String id = p.path("id").asText();
        assertThat(p.path("definition").path("currency").asText()).isEqualTo("USD");
        var plan =
                Map.of(
                        "expectedRevision",
                        0,
                        "name",
                        "Vietnam Plan",
                        "regionIds",
                        List.of("VN"),
                        "tenantIds",
                        List.of(partner.getIdAsString()),
                        "marketConfiguration",
                        Map.of("salePrice", "0.10", "taxRate", "5", "surcharge", "0.20"));
        p = call("POST", "/api/v1/propositions/" + id + "/plans", plan, 201);
        String planId = p.path("plans").get(0).path("id").asText();
        assertThat(p.path("plans").get(0).path("marketConfiguration").path("totalPrice").asText())
                .isEqualTo("0.31");
        call("POST", "/api/v1/propositions/" + id + "/plans", plan, 409);
        p =
                call(
                        "POST",
                        "/api/v1/propositions/" + id + "/plans/" + planId + "/submit",
                        Map.of("expectedRevision", 1),
                        200);
        assertThat(p.path("plans").get(0).path("status").asText()).isEqualTo("PENDING_REVIEW");
        p =
                call(
                        "POST",
                        "/api/v1/propositions/" + id + "/plans/" + planId + "/approve",
                        Map.of("expectedRevision", 2),
                        200);
        assertThat(p.path("plans").get(0).path("status").asText()).isEqualTo("APPROVED");
        assertThat(p.path("plans").get(0).path("active").asBoolean()).isFalse();
        assertThat(p.path("status").asText()).isEqualTo("DRAFT");
        p =
                call(
                        "POST",
                        "/api/v1/propositions/" + id + "/submit",
                        Map.of("expectedRevision", 3),
                        200);
        p =
                call(
                        "POST",
                        "/api/v1/propositions/" + id + "/approve",
                        Map.of("expectedRevision", 4),
                        200);
        assertThat(p.path("plans").get(0).path("active").asBoolean()).isTrue();
        var first = propositions.findById(new ObjectId(id)).orElseThrow();
        var stale = propositions.findById(new ObjectId(id)).orElseThrow();
        propositions.save(first);
        assertThatThrownBy(() -> propositions.save(stale))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(propositions.findById(new ObjectId(id)).orElseThrow().getChanges()).hasSize(6);
        if (Boolean.getBoolean("proposition.browser"))
            browser(source.path("productProcessId").asText());
    }

    private TenantEntity tenant(String name, String type, TenantTypeEnum tenantType) {
        return TenantEntity.builder()
                .tenantName(name)
                .partnerType(new PartnerType(type, type))
                .tenantType(tenantType)
                .contact(
                        Contact.builder()
                                .communications(
                                        List.of(
                                                Communication.builder()
                                                        .preferred(true)
                                                        .communicationDetails(
                                                                List.of(
                                                                        CommunicationDetail
                                                                                .builder()
                                                                                .preferred(true)
                                                                                .isoCountryCode(
                                                                                        "BRAND"
                                                                                                        .equals(
                                                                                                                type)
                                                                                                ? "TH"
                                                                                                : "VN")
                                                                                .build()))
                                                        .build()))
                                .build())
                .build();
    }

    private PartyToPartyRelationship relation(ObjectId target) {
        return PartyToPartyRelationship.builder()
                .toPartyId(target)
                .relationshipTypeCode("MT")
                .build();
    }

    private JsonNode call(String method, String path, Object body, int expected) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                        .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as(method + " " + path + ": " + response.body())
                .isEqualTo(expected);
        return json.readTree(response.body());
    }

    private void browser(String processId) throws Exception {
        var root = Path.of("..").toAbsolutePath().normalize();
        var config = root.resolve("EcoFood-FE/tests/proposition-live-config.json");
        var artifacts = root.resolve(".product-build");
        Files.createDirectories(artifacts);
        int width = Integer.getInteger("proposition.browserWidth", 1440);
        String stem = "scr33-live-" + width;
        try {
            Files.writeString(
                    config,
                    json.writeValueAsString(
                            Map.of(
                                    "baseURL",
                                    "http://localhost:" + port,
                                    "token",
                                    token,
                                    "processId",
                                    processId)));
            var chrome =
                    new ProcessBuilder(
                                    "node",
                                    root.resolve("EcoFood-FE/tests/run-proposition-browser.mjs")
                                            .toString(),
                                    "http://localhost:5173/tests/proposition-live.html",
                                    Integer.toString(width),
                                    artifacts.toString(),
                                    stem)
                            .redirectOutput(artifacts.resolve(stem + "-result.log").toFile())
                            .redirectError(artifacts.resolve(stem + "-runner.log").toFile())
                            .start();
            if (!chrome.waitFor(45, TimeUnit.SECONDS)) {
                chrome.destroyForcibly();
                fail("Browser timed out");
            }
            assertThat(chrome.exitValue())
                    .as("Browser runner; inspect result/runner logs")
                    .isZero();
            assertThat(Files.readString(artifacts.resolve(stem + "-dom.html")))
                    .contains("data-test-result=\"passed\"");
        } finally {
            Files.deleteIfExists(config);
        }
    }
}
