package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.auth.service.*;
import com.ecofood.app.customer.controller.CustomerPortalController;
import com.ecofood.app.customer.entity.CustomerProfileEntity;
import com.ecofood.app.customer.repository.CustomerProfileRepository;
import com.ecofood.app.customer.service.*;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.repository.UserRepository;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles({"customer-auth-security-test", "customer-profile-security-test"})
@ContextConfiguration(classes = CustomerProfileSecurityTest.Config.class)
@TestPropertySource(properties = {"jwt.secret=" + CustomerAuthSecurityTest.SECRET, "jwt.issuer=app", "jwt.audience=app-api",
    "jwt.tokenValidityInSeconds=1800", "jwt.refreshTokenValidityInSeconds=86400"})
class CustomerProfileSecurityTest {
  static final String BASE = "/api/v1/customers/me";
  static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
  @Autowired WebApplicationContext context;
  @Autowired MongoTemplate mongo;
  @Autowired UserRepository users;
  @Autowired CustomerProfileRepository profiles;
  @Autowired KeycloakTokenVerifier verifier;
  @Autowired CustomerKeycloakAccountService keycloak;
  @Autowired InternalTokenService tokens;
  @Autowired TokenBlacklistService blacklist;
  final Map<String, UserEntity> accounts = new ConcurrentHashMap<>();
  final Map<String, CustomerProfileEntity> rows = new ConcurrentHashMap<>();
  final Set<String> revoked = new HashSet<>();
  MockMvc mvc;

  @BeforeEach void setup() {
    reset(mongo, users, profiles, verifier, keycloak, blacklist); accounts.clear(); rows.clear(); revoked.clear();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenAnswer(call -> accounts.values().stream()
        .filter(user -> user.getExternalLoginId().equals(((Query) call.getArgument(0)).getQueryObject().get("externalLoginId"))).findFirst().orElse(null));
    when(users.findById(any(ObjectId.class))).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0).toString())));
    when(users.findByEmailIgnoreCase(anyString())).thenAnswer(call -> accounts.values().stream().filter(user -> user.getEmail().equalsIgnoreCase(call.getArgument(0))).findFirst());
    when(users.insert(any(UserEntity.class))).thenAnswer(call -> {
      UserEntity user = call.getArgument(0); user.setId(new ObjectId()); user.setCreatedAt(NOW.minusSeconds(3600)); accounts.put(user.getIdAsString(), user); return user;
    });
    when(profiles.findByTenantIdAndUserIdAndDeletedNot(any(), any(), eq(true))).thenAnswer(call -> {
      assertEquals(new ObjectId(CustomerIdentityServiceTest.TENANT), call.getArgument(0));
      return Optional.ofNullable(rows.get(call.getArgument(1).toString()));
    });
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(CustomerProfileEntity.class))).thenAnswer(call -> {
      Query query = call.getArgument(0); Update update = call.getArgument(1); Document predicates = query.getQueryObject();
      ObjectId userId = predicates.getObjectId("userId"), tenantId = predicates.getObjectId("tenantId");
      assertEquals(new ObjectId(CustomerIdentityServiceTest.TENANT), tenantId);
      assertEquals(new Document("$ne", true), predicates.get("deleted"));
      var row = rows.computeIfAbsent(userId.toHexString(), ignored -> {
        var value = CustomerProfileEntity.builder().userId(userId).tenantId(tenantId).build(); value.setId(new ObjectId()); return value;
      });
      Document defaults = update.getUpdateObject().get("$setOnInsert", Document.class);
      if (row.getFirstName() == null) row.setFirstName(defaults.getString("firstName"));
      if (row.getLastName() == null) row.setLastName(defaults.getString("lastName"));
      Document values = update.getUpdateObject().get("$set", Document.class);
      if (values.containsKey("firstName")) row.setFirstName(values.getString("firstName"));
      if (values.containsKey("lastName")) row.setLastName(values.getString("lastName"));
      if (values.containsKey("phoneNumber")) row.setPhoneNumber(values.getString("phoneNumber"));
      if (values.containsKey("address")) row.setAddress(values.getString("address"));
      row.setUpdatedAt((Instant) values.get("updatedAt")); row.setUpdatedBy(values.getString("updatedBy")); return row;
    });
    when(blacklist.isRevoked(anyString())).thenAnswer(call -> revoked.contains(call.getArgument(0)));
    doAnswer(call -> { revoked.add(call.getArgument(0)); return null; }).when(blacklist).revoke(anyString(), anyLong());
  }

  @Test void customerReadsUpdatesAndReloadsOwnProfileWithoutChangingIdentityOrCreatingAnotherUser() throws Exception {
    var pair = login("buyer-a"); String access = pair.get("accessToken").asText(); String customer = tokens.verifyToken(access).getSubject();
    mvc.perform(get(BASE).header("Authorization", "Bearer " + access)).andExpect(status().isOk())
        .andExpect(jsonPath("$.customerId").value(customer)).andExpect(jsonPath("$.email").value("buyer-a@example.test"))
        .andExpect(jsonPath("$.createdAt").value(NOW.minusSeconds(3600).toString()))
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    assertTrue(rows.isEmpty());
    mvc.perform(patch(BASE).header("Authorization", "Bearer " + access).contentType("application/json")
        .content("{\"firstName\":\"  Minh  \",\"lastName\":\"Nguyen\",\"phoneNumber\":\"+84901234567\",\"address\":\"Hà Nội\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Minh Nguyen"))
        .andExpect(jsonPath("$.updatedAt").value(NOW.toString()));
    mvc.perform(get(BASE).header("Authorization", "Bearer " + access)).andExpect(status().isOk())
        .andExpect(jsonPath("$.phoneNumber").value("+84901234567")).andExpect(jsonPath("$.customerId").value(customer));
    assertEquals(1, accounts.size()); assertEquals(1, rows.size()); assertEquals("buyer-a", accounts.get(customer).getExternalLoginId());
    assertEquals("CUSTOMER", accounts.get(customer).getUserCategory().getUserCategoryCode());
    login("buyer-a"); verify(users, times(1)).insert(any(UserEntity.class)); verify(users, never()).save(any());
  }

  @Test void protectedFieldsIncludingNullAreRejectedWithoutMutation() throws Exception {
    String access = login("buyer-a").get("accessToken").asText();
    for (String field : List.of("email", "password", "role", "roles", "permissions", "userCategory", "tenantId", "customerId", "userId", "externalLoginId", "subject", "keycloakSubject", "emailVerified", "createdAt", "updatedAt", "avatar")) {
      for (String value : List.of("\"other\"", "null"))
        mvc.perform(patch(BASE).header("Authorization", "Bearer " + access).contentType("application/json")
            .content("{\"firstName\":\"Allowed\",\"" + field + "\":" + value + "}"))
            .andExpect(status().isBadRequest());
    }
    assertTrue(rows.isEmpty()); verify(profiles, never()).save(any());
  }

  @Test void customerCannotReadOrModifyAnotherCustomerThroughQueryOrPath() throws Exception {
    String a = login("buyer-a").get("accessToken").asText(), b = login("buyer-b").get("accessToken").asText();
    mvc.perform(patch(BASE).header("Authorization", "Bearer " + a).contentType("application/json").content("{\"firstName\":\"Private A\"}"))
        .andExpect(status().isOk());
    String aId = tokens.verifyToken(a).getSubject();
    mvc.perform(get(BASE).header("Authorization", "Bearer " + b).param("customerId", aId).param("tenantId", "other"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("buyer-b@example.test"));
    mvc.perform(patch(BASE).header("Authorization", "Bearer " + b).param("customerId", aId).contentType("application/json").content("{\"firstName\":\"B\"}"))
        .andExpect(status().isOk());
    assertEquals("Private A", rows.get(aId).getFirstName()); assertEquals(2, rows.size());
    mvc.perform(get(BASE + "/" + aId).header("Authorization", "Bearer " + b)).andExpect(status().isForbidden());
  }

  @Test void inputValidationAndEmptyPatchAreSafe() throws Exception {
    String access = login("buyer-a").get("accessToken").asText();
    for (String body : List.of("{\"firstName\":\"  \"}", "{\"lastName\":\"" + "a".repeat(81) + "\"}",
        "{\"phoneNumber\":\"-------\"}", "{\"phoneNumber\":\"not-a-number\"}", "{\"firstName\":\"Line\\nBreak\"}", "[]", "{broken"))
      mvc.perform(patch(BASE).header("Authorization", "Bearer " + access).contentType("application/json").content(body)).andExpect(status().isBadRequest());
    mvc.perform(patch(BASE).header("Authorization", "Bearer " + access).contentType("application/json").content("{}"))
        .andExpect(status().isOk()); assertTrue(rows.isEmpty());
  }

  @Test void requiresAuthenticationUpdatePermissionAndActiveVerifiedIdentity() throws Exception {
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    mvc.perform(patch(BASE).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    String access = login("buyer-a").get("accessToken").asText();
    String readOnly = signed(access, Map.of("permissions", List.of("CUSTOMER_SELF_READ")));
    mvc.perform(get(BASE).header("Authorization", "Bearer " + readOnly)).andExpect(status().isOk());
    mvc.perform(patch(BASE).header("Authorization", "Bearer " + readOnly).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    String employee = signed(access, Map.of("identityType", "EMPLOYEE"));
    mvc.perform(get(BASE).header("Authorization", "Bearer " + employee)).andExpect(status().isForbidden());
    String wrongTenant = signed(access, Map.of("tenantId", "000000000000000000000099"));
    mvc.perform(get(BASE).header("Authorization", "Bearer " + wrongTenant)).andExpect(status().isForbidden());
    doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account unavailable")).when(keycloak).requireActiveVerified(any(), anyString(), anyString());
    mvc.perform(get(BASE).header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
    assertTrue(rows.isEmpty());
  }

  @Test void logoutRevokesProfileAccessAndCorsAllowsPatchOnlyForTrustedOrigin() throws Exception {
    var pair = login("buyer-a"); String access = pair.get("accessToken").asText();
    mvc.perform(options(BASE).header("Origin", "http://customerportal.ecofood.localhost:5173")
        .header("Access-Control-Request-Method", "PATCH").header("Access-Control-Request-Headers", "Authorization,Content-Type"))
        .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
    mvc.perform(options(BASE).header("Origin", "http://evil.example").header("Access-Control-Request-Method", "PATCH"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/auth/logout").contentType("application/json")
        .content("{\"accessToken\":\"" + access + "\",\"refreshToken\":\"" + pair.get("refreshToken").asText() + "\"}"))
        .andExpect(status().isOk());
    mvc.perform(get(BASE).header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
  }

  private com.fasterxml.jackson.databind.JsonNode login(String subject) throws Exception {
    when(verifier.verifyForIssuer(subject, CustomerIdentityServiceTest.ISSUER)).thenReturn(new JWTClaimsSet.Builder(CustomerIdentityServiceTest.claims())
        .subject(subject).claim("email", subject + "@example.test").claim("given_name", "Customer").claim("family_name", "Name").build());
    var result = mvc.perform(post("/api/v1/customer-auth/exchange").contentType("application/json").content("{\"accessToken\":\"" + subject + "\"}"))
        .andExpect(status().isOk()).andReturn();
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
  }
  private String signed(String access, Map<String, Object> changed) {
    var claims = new HashMap<String, Object>(tokens.verifyToken(access)); claims.putAll(changed);
    return io.jsonwebtoken.Jwts.builder().claims(claims).id(UUID.randomUUID().toString())
        .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(CustomerAuthSecurityTest.SECRET))).compact();
  }

  @Configuration @Profile("customer-profile-security-test") @Import(CustomerAuthSecurityTest.Config.class)
  static class Config {
    @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    @Bean CustomerProfileRepository profiles() { return mock(CustomerProfileRepository.class); }
    @Bean CustomerPortalService profileService(CustomerIdentityService identities, CustomerProfileRepository profiles, MongoTemplate mongo, Clock clock) {
      return new CustomerPortalService(identities, profiles, mongo, clock);
    }
    @Bean CustomerPortalController profileController(CustomerPortalService service) { return new CustomerPortalController(service, mock(CustomerPurchaseService.class)); }
  }
}
