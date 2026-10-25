package com.ecofood.app.auth.controller;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecofood.app.auth.service.InternalTokenService;
import com.ecofood.app.auth.service.InternalTokenService.TokenPair;
import com.ecofood.app.auth.service.KeycloakTokenVerifier;
import com.ecofood.core.security.AuthenticationContext;
import com.nimbusds.jwt.JWTClaimsSet;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

  // 32+ raw bytes, base64-encoded — matches AuthenticationContext/jwt.secret's
  // documented >=32-byte requirement for HS256 (see AuthenticationContextTest).
  private static final String SECRET =
      java.util.Base64.getEncoder().encodeToString("unit-test-only-hmac-secret-0123456789".getBytes());

  @Mock private KeycloakTokenVerifier keycloakTokenVerifier;
  @Mock private InternalTokenService internalTokenService;
  @Mock private Claims refreshClaims;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(keycloakTokenVerifier, internalTokenService)).build();
  }

  @AfterEach
  void unloadAuthenticationContext() {
    AuthenticationContext.unload();
  }

  /** switchTenant() reads the caller's id from AuthenticationContext, same as a real InternalJwtRequestFilter-processed request would. */
  private void authenticateAs(String userId) {
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

  private JWTClaimsSet keycloakClaims(String email, String issuer) {
    return new JWTClaimsSet.Builder().issuer(issuer).claim("email", email).build();
  }

  @Test
  void exchangeReturns200WithBothTokensAndKeycloakClaims() throws Exception {
    when(keycloakTokenVerifier.verify("kc-token"))
        .thenReturn(keycloakClaims("jane.doe@example.com", "http://localhost:8080/realms/acme"));
    when(internalTokenService.mintTokenPairForKeycloakLogin("jane.doe@example.com", "acme"))
        .thenReturn(new TokenPair("access-1", "refresh-1"));

    mockMvc
        .perform(
            post("/api/v1/auth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\":\"kc-token\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken", is("access-1")))
        .andExpect(jsonPath("$.refreshToken", is("refresh-1")))
        .andExpect(jsonPath("$.keycloakClaims.email", is("jane.doe@example.com")));
  }

  @Test
  void exchangeReturns401WhenKeycloakVerificationFails() throws Exception {
    when(keycloakTokenVerifier.verify(anyString())).thenThrow(new IllegalArgumentException("bad token"));

    mockMvc
        .perform(
            post("/api/v1/auth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\":\"bad-token\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message", is("Unauthorized")));
  }

  @Test
  void exchangeReturns401WhenEmailClaimIsMissing() throws Exception {
    when(keycloakTokenVerifier.verify("kc-token"))
        .thenReturn(new JWTClaimsSet.Builder().issuer("http://localhost:8080/realms/acme").build());

    mockMvc
        .perform(
            post("/api/v1/auth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\":\"kc-token\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void exchangeReturns401WhenUserHasNoAssignmentToTheIssuingTenant() throws Exception {
    when(keycloakTokenVerifier.verify("kc-token"))
        .thenReturn(keycloakClaims("jane.doe@example.com", "http://localhost:8080/realms/acme"));
    when(internalTokenService.mintTokenPairForKeycloakLogin(anyString(), anyString()))
        .thenThrow(new IllegalArgumentException("no assignment"));

    mockMvc
        .perform(
            post("/api/v1/auth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\":\"kc-token\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void exchangeReturns400WhenAccessTokenIsBlank() throws Exception {
    mockMvc
        .perform(post("/api/v1/auth/exchange").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void refreshTokenReturns200WithNewPairAndNullKeycloakClaims() throws Exception {
    when(internalTokenService.verifyRefreshToken("old-refresh")).thenReturn(refreshClaims);
    when(internalTokenService.mintTokenPair(refreshClaims)).thenReturn(new TokenPair("access-2", "refresh-2"));

    mockMvc
        .perform(
            post("/api/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"old-refresh\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken", is("access-2")))
        .andExpect(jsonPath("$.keycloakClaims", nullValue()));
  }

  @Test
  void refreshTokenReturns401WhenRefreshTokenIsInvalid() throws Exception {
    when(internalTokenService.verifyRefreshToken(anyString()))
        .thenThrow(new IllegalArgumentException("Invalid internal token"));

    mockMvc
        .perform(
            post("/api/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"garbage\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutAlwaysReturns200AndRevokesBothTokens() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\":\"a\",\"refreshToken\":\"r\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message", is("Logged out")));

    verify(internalTokenService).revoke("a");
    verify(internalTokenService).revoke("r");
  }

  @Test
  void switchTenantReturns200WithANewPairForTheAuthenticatedCaller() throws Exception {
    authenticateAs("671f1a2b3c4d5e6f7a8b9c0d");
    when(internalTokenService.mintTokenPair("671f1a2b3c4d5e6f7a8b9c0d", "671f0a1a2b3c4d5e6f7a8b05"))
        .thenReturn(new TokenPair("access-3", "refresh-3"));

    mockMvc
        .perform(
            post("/api/v1/auth/switch-tenant")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"671f0a1a2b3c4d5e6f7a8b05\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken", is("access-3")))
        .andExpect(jsonPath("$.refreshToken", is("refresh-3")))
        .andExpect(jsonPath("$.keycloakClaims", nullValue()));
  }

  @Test
  void switchTenantReturns401WhenCallerHasNoAssignmentToTheTargetTenant() throws Exception {
    authenticateAs("671f1a2b3c4d5e6f7a8b9c0d");
    when(internalTokenService.mintTokenPair(anyString(), anyString()))
        .thenThrow(new IllegalArgumentException("no assignment"));

    mockMvc
        .perform(
            post("/api/v1/auth/switch-tenant")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"671f0a1a2b3c4d5e6f7a8b05\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void switchTenantReturns400WhenTenantIdIsBlank() throws Exception {
    mockMvc
        .perform(post("/api/v1/auth/switch-tenant").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }
}
