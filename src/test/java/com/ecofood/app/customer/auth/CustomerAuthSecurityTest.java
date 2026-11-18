package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.auth.controller.AuthController;
import com.ecofood.app.auth.security.InternalJwtAuthenticationEntryPoint;
import com.ecofood.app.auth.security.InternalJwtRequestFilter;
import com.ecofood.app.auth.service.*;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.config.SecurityConfig;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.core.client.*;
import com.ecofood.core.dto.UserQueryResponse;
import com.ecofood.core.dto.PartyRelationshipResponse;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles("customer-auth-security-test")
@ContextConfiguration(classes = CustomerAuthSecurityTest.Config.class)
@TestPropertySource(properties = {"jwt.secret=" + CustomerAuthSecurityTest.SECRET, "jwt.issuer=app", "jwt.audience=app-api",
    "jwt.tokenValidityInSeconds=1800", "jwt.refreshTokenValidityInSeconds=86400"})
class CustomerAuthSecurityTest {
  static final String SECRET = "dGVzdC1vbmx5LWZlZWRiYWNrLXNlY3VyaXR5LXRlc3Qta2V5";
  @Autowired WebApplicationContext context;
  @Autowired UserRepository users;
  @Autowired MongoTemplate mongo;
  @Autowired KeycloakTokenVerifier verifier;
  @Autowired CustomerKeycloakAccountService keycloakAccounts;
  @Autowired InternalTokenService tokens;
  @Autowired TokenBlacklistService blacklist;
  @Autowired CoreUserQueryClient userQuery;
  @Autowired CoreUserAccessClient userAccess;
  private MockMvc mvc;
  private AtomicReference<UserEntity> stored;

  @Test void permitsConfiguredPortalCorsWithoutRemovingExistingLocalOrigins() throws Exception {
    for (String origin : List.of("http://customerportal.ecofood.localhost:5173", "https://customerportal.ecofood.localhost:5173", "http://localhost:5173")) {
      mvc.perform(options("/api/v1/customer-auth/me").header("Origin", origin)
          .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "Authorization"))
          .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin));
    }
    mvc.perform(options("/api/v1/customer-auth/me").header("Origin", "https://untrusted.example")
        .header("Access-Control-Request-Method", "GET")).andExpect(status().isForbidden());
  }

  @BeforeEach void setup() {
    reset(users, mongo, verifier, keycloakAccounts, blacklist, userQuery, userAccess);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    stored = new AtomicReference<>();
    when(verifier.verifyForIssuer("keycloak-access", CustomerIdentityServiceTest.ISSUER)).thenReturn(CustomerIdentityServiceTest.claims());
    when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenAnswer(call -> stored.get());
    when(users.insert(any(UserEntity.class))).thenAnswer(call -> {
      UserEntity user = call.getArgument(0); user.setId(new org.bson.types.ObjectId()); stored.set(user); return user;
    });
    when(users.findById(any(org.bson.types.ObjectId.class))).thenAnswer(call -> Optional.ofNullable(stored.get())
        .filter(user -> user.getId().equals(call.getArgument(0))));
    Set<String> revoked = ConcurrentHashMap.newKeySet();
    doAnswer(call -> { revoked.add(call.getArgument(0)); return null; }).when(blacklist).revoke(anyString(), anyLong());
    when(blacklist.isRevoked(anyString())).thenAnswer(call -> revoked.contains(call.getArgument(0)));
  }

  @Test void verifiedSelfRegistrationMintsMinimalPortalJwtAndLoginAgainDoesNotDuplicate() throws Exception {
    var pair = exchange();
    var claims = tokens.verifyToken(pair.get("accessToken").asText());
    assertEquals(stored.get().getIdAsString(), claims.getSubject());
    assertEquals("CUSTOMER", claims.get("identityType")); assertEquals("CUSTOMER", claims.get("userCategory"));
    assertEquals(List.of("CUSTOMER"), claims.get("roles")); assertEquals(List.of("CUSTOMER"), claims.get("roleGroups"));
    assertEquals(List.of("CUSTOMER_SELF_READ", "CUSTOMER_INVOICE_CLAIM", "CUSTOMER_PROFILE_UPDATE", "CUSTOMER_NOTIFICATION_MANAGE", "COMPLAINT_CREATE", "COMPLAINT_READ"), claims.get("permissions"));
    assertEquals(CustomerIdentityServiceTest.TENANT, claims.get("tenantId"));
    var refresh = tokens.verifyRefreshToken(pair.get("refreshToken").asText());
    assertEquals(claims.getId(), refresh.getId()); assertNull(refresh.get("roles")); assertNull(refresh.get("permissions"));
    exchange(); verify(users, times(1)).insert(any(UserEntity.class));
    mvc.perform(get("/api/v1/customer-auth/me").header("Authorization", "Bearer " + pair.get("accessToken").asText()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(stored.get().getIdAsString()))
        .andExpect(jsonPath("$.userCategory").value("CUSTOMER"))
        .andExpect(jsonPath("$.tenantId").value(CustomerIdentityServiceTest.TENANT));
  }

  @Test void customerJwtCannotReachTenantApisOrSwitchTenantEvenIfTheExistingRouteIsPublic() throws Exception {
    String access = exchange().get("accessToken").asText();
    for (String path : List.of("/api/v1/feedback", "/api/v1/transactions", "/api/v1/users/directory", "/api/v1/customers/me/other-user"))
      mvc.perform(get(path).header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/auth/switch-tenant").header("Authorization", "Bearer " + access)
        .contentType("application/json").content("{\"tenantId\":\"000000000000000000000001\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + access).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test void refreshChecksAccountAndLogoutRevokesBothTokens() throws Exception {
    var first = exchange();
    var refreshed = mvc.perform(post("/api/v1/customer-auth/refresh-token").contentType("application/json")
        .content("{\"refreshToken\":\"" + first.get("refreshToken").asText() + "\"}"))
        .andExpect(status().isOk()).andReturn();
    var pair = new com.fasterxml.jackson.databind.ObjectMapper().readTree(refreshed.getResponse().getContentAsString());
    String access = pair.get("accessToken").asText(), refresh = pair.get("refreshToken").asText();
    mvc.perform(post("/api/v1/auth/refresh-token").contentType("application/json")
        .content("{\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/logout").contentType("application/json")
        .content("{\"accessToken\":\"" + access + "\",\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/customer-auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/customer-auth/refresh-token").contentType("application/json")
        .content("{\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test void existingEmployeeExchangeCannotBypassCustomerIsolationDespitePortalTenantMembership() throws Exception {
    exchange();
    var user = stored.get();
    var response = UserQueryResponse.builder().id(user.getIdAsString()).email(user.getEmail()).username(user.getUsername())
        .partyRelationships(List.of(PartyRelationshipResponse.builder().toPartyId(CustomerIdentityServiceTest.TENANT)
            .relationshipTypeCode("TENANT").build())).build();
    when(userQuery.getByEmail(user.getEmail())).thenReturn(response);
    when(verifier.verify("customer-via-employee-login")).thenReturn(CustomerIdentityServiceTest.claims());
    mvc.perform(post("/api/v1/auth/exchange").contentType("application/json")
        .content("{\"accessToken\":\"customer-via-employee-login\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(userAccess);
  }

  @Test void adminOfTheSamePortalTenantKeepsExistingEmployeeLoginAndAcl() throws Exception {
    var admin = CustomerIdentityServiceTest.user(); admin.setCustomerIdentityIssuer(null); admin.setCustomerEmailNormalized(null);
    admin.setUserCategory(com.ecofood.app.userACL.entity.UserCategory.builder().userCategoryCode("PARTNER").build());
    admin.setEmail("admin@example.test"); stored.set(admin);
    var response = UserQueryResponse.builder().id(admin.getIdAsString()).email(admin.getEmail()).username("portal-admin")
        .partyRelationships(List.of(PartyRelationshipResponse.builder().toPartyId(CustomerIdentityServiceTest.TENANT)
            .relationshipTypeCode("TENANT").build())).build();
    when(userQuery.getByEmail(admin.getEmail())).thenReturn(response);
    when(userQuery.getById(admin.getIdAsString())).thenReturn(response);
    when(userAccess.resolveAccess(admin.getId(), new org.bson.types.ObjectId(CustomerIdentityServiceTest.TENANT)))
        .thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"), List.of("TRANSACTION_READ"), List.of("TENANT")));
    when(verifier.verify("portal-admin-login")).thenReturn(new com.nimbusds.jwt.JWTClaimsSet.Builder(CustomerIdentityServiceTest.claims())
        .claim("email", admin.getEmail()).build());
    var result = mvc.perform(post("/api/v1/auth/exchange").contentType("application/json")
        .content("{\"accessToken\":\"portal-admin-login\"}"))
        .andExpect(status().isOk()).andReturn();
    String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    var claims = tokens.verifyToken(token);
    assertNull(claims.get("identityType")); assertEquals(List.of("TENANT"), claims.get("roleGroups"));
    assertEquals(List.of("TRANSACTION_READ"), claims.get("permissions"));
  }

  @Test void customerTokensForAnotherTenantCannotReadAccountOrRefreshUnderConfiguredPortal() throws Exception {
    exchange();
    var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    String access = Jwts.builder().subject(stored.get().getIdAsString()).id("other-tenant-access")
        .claim("identityType", "CUSTOMER").claim("tokenType", "access")
        .claim("tenantId", "000000000000000000000099").claim("clientId", CustomerIdentityServiceTest.CLIENT).signWith(key).compact();
    mvc.perform(get("/api/v1/customer-auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
    String refresh = Jwts.builder().subject(stored.get().getIdAsString()).id("other-tenant-refresh")
        .claim("identityType", "CUSTOMER").claim("tokenType", "refresh")
        .claim("tenantId", "000000000000000000000099").claim("clientId", CustomerIdentityServiceTest.CLIENT).signWith(key).compact();
    mvc.perform(post("/api/v1/customer-auth/refresh-token").contentType("application/json")
        .content("{\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test void noTokenWrongSignatureUnverifiedAndDisabledAccountsCannotAuthenticate() throws Exception {
    mvc.perform(get("/api/v1/customer-auth/me")).andExpect(status().isUnauthorized());
    when(verifier.verifyForIssuer("bad", CustomerIdentityServiceTest.ISSUER)).thenThrow(new IllegalArgumentException("Invalid signature"));
    mvc.perform(post("/api/v1/customer-auth/exchange").contentType("application/json").content("{\"accessToken\":\"bad\"}"))
        .andExpect(status().isUnauthorized());
    verify(users, never()).insert(any(UserEntity.class));
    var pair = exchange(); stored.get().setDeleted(true);
    mvc.perform(post("/api/v1/customer-auth/refresh-token").contentType("application/json")
        .content("{\"refreshToken\":\"" + pair.get("refreshToken").asText() + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test void employeeTokensAreNotReclassifiedAndCannotUseCustomerRefresh() throws Exception {
    var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    String employee = Jwts.builder().subject("employee").id("employee-jti").claim("tokenType", "access")
        .claim("tenantId", "000000000000000000000001").claim("roleGroups", List.of("TENANT")).signWith(key).compact();
    mvc.perform(get("/api/v1/customer-auth/me").header("Authorization", "Bearer " + employee)).andExpect(status().isUnauthorized());
    String refresh = Jwts.builder().subject("employee").id("employee-jti").claim("tokenType", "refresh").signWith(key).compact();
    mvc.perform(post("/api/v1/customer-auth/refresh-token").contentType("application/json")
        .content("{\"refreshToken\":\"" + refresh + "\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(users);
  }

  private com.fasterxml.jackson.databind.JsonNode exchange() throws Exception {
    var result = mvc.perform(post("/api/v1/customer-auth/exchange").contentType("application/json")
        .content("{\"accessToken\":\"keycloak-access\"}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn();
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
  }

  @Configuration
  @Profile("customer-auth-security-test")
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfig.class, GlobalExceptionHandler.class})
  static class Config {
    @Bean CustomerAuthProperties properties() { return CustomerIdentityServiceTest.properties(); }
    @Bean CoreTenantQueryClient portalTenantClient() {
      var client = mock(CoreTenantQueryClient.class); var tenant = CustomerTenantResolverTest.tenant();
      when(client.getById(tenant.getId())).thenReturn(tenant);
      when(client.getAllByRealm(tenant.getRealm())).thenReturn(List.of(tenant));
      return client;
    }
    @Bean CustomerTenantResolver portalResolver(CustomerAuthProperties properties, CoreTenantQueryClient client) {
      return new CustomerTenantResolver(properties, client);
    }
    @Bean UserRepository users() { return mock(UserRepository.class); }
    @Bean MongoTemplate mongo() { return mock(MongoTemplate.class); }
    @Bean CustomerKeycloakAccountService keycloakAccounts() { return mock(CustomerKeycloakAccountService.class); }
    @Bean CustomerIdentityService identities(UserRepository users, MongoTemplate mongo, CustomerTenantResolver resolver, CustomerKeycloakAccountService accounts) {
      return new CustomerIdentityService(users, mongo, resolver, accounts);
    }
    @Bean KeycloakTokenVerifier verifier() { return mock(KeycloakTokenVerifier.class); }
    @Bean TokenBlacklistService blacklist() { return mock(TokenBlacklistService.class); }
    @Bean CoreUserQueryClient userQuery() { return mock(CoreUserQueryClient.class); }
    @Bean CoreUserAccessClient userAccess() { return mock(CoreUserAccessClient.class); }
    @Bean InternalTokenService tokens(TokenBlacklistService blacklist, CustomerIdentityService identities,
        CoreTenantQueryClient tenants, CoreUserQueryClient query, CoreUserAccessClient access) {
      return new InternalTokenService(tenants, query, access, blacklist, identities);
    }
    @Bean CustomerAuthController customerController(CustomerTenantResolver resolver, KeycloakTokenVerifier verifier, CustomerIdentityService identities, InternalTokenService tokens) {
      return new CustomerAuthController(resolver, verifier, identities, tokens);
    }
    @Bean AuthController authController(KeycloakTokenVerifier verifier, InternalTokenService tokens) { return new AuthController(verifier, tokens); }
    @Bean InternalJwtRequestFilter filter(InternalTokenService tokens) { return new InternalJwtRequestFilter(tokens); }
    @Bean InternalJwtAuthenticationEntryPoint entryPoint() { return new InternalJwtAuthenticationEntryPoint(); }
  }
}
