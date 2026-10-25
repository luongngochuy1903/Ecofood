package com.ecofood.app.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.app.auth.service.InternalTokenService.TokenPair;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.client.CoreUserAccessClient;
import com.ecofood.core.client.CoreUserQueryClient;
import com.ecofood.core.dto.PartyRelationshipResponse;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.dto.UserQueryResponse;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InternalTokenServiceTest {

  private static final String SECRET =
      java.util.Base64.getEncoder().encodeToString("unit-test-only-hmac-secret-0123456789".getBytes());

  @Mock private CoreTenantQueryClient tenantQueryClient;
  @Mock private CoreUserQueryClient userQueryClient;
  @Mock private CoreUserAccessClient userAccessClient;
  @Mock private TokenBlacklistService tokenBlacklistService;

  private InternalTokenService internalTokenService;

  private ObjectId userId;
  private ObjectId tenantId;
  private UserQueryResponse user;
  private TenantResponse tenant;

  @BeforeEach
  void setUp() {
    internalTokenService =
        new InternalTokenService(tenantQueryClient, userQueryClient, userAccessClient, tokenBlacklistService);
    ReflectionTestUtils.setField(internalTokenService, "secret", SECRET);
    ReflectionTestUtils.setField(internalTokenService, "tokenValidityInSeconds", 1800L);
    ReflectionTestUtils.setField(internalTokenService, "refreshTokenValidityInSeconds", 86400L);
    ReflectionTestUtils.setField(internalTokenService, "issuer", "app");
    ReflectionTestUtils.setField(internalTokenService, "audience", "app-api");

    userId = new ObjectId();
    tenantId = new ObjectId();
    user =
        UserQueryResponse.builder()
            .id(userId.toHexString())
            .username("jane.doe")
            .email("jane@example.com")
            .partyRelationships(
                List.of(
                    PartyRelationshipResponse.builder()
                        .toPartyId(tenantId.toHexString())
                        .relationshipTypeCode("TENANT")
                        .build()))
            .build();
    tenant =
        TenantResponse.builder().id(tenantId.toHexString()).realm("acme-realm").clientId("acme-client").build();
  }

  private void stubLookups() {
    when(userQueryClient.getById(userId.toHexString())).thenReturn(user);
    when(tenantQueryClient.getById(tenantId.toHexString())).thenReturn(tenant);
    when(userAccessClient.resolveAccess(userId, tenantId))
        .thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"), List.of("tenants.read.any"), List.of("TENANT")));
  }

  private Claims decode(String token) {
    return Jwts.parser()
        .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(SECRET)))
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }

  @Test
  void mintTokenPairBuildsAccessTokenWithFullClaimsAndCorrectTokenType() {
    stubLookups();

    TokenPair pair = internalTokenService.mintTokenPair(userId.toHexString(), tenantId.toHexString());
    Claims accessClaims = decode(pair.accessToken());

    assertThat(accessClaims.get("email")).isEqualTo("jane@example.com");
    assertThat(accessClaims.get("tenantId")).isEqualTo(tenantId.toHexString());
    assertThat(accessClaims.get("tenantCode")).isEqualTo("acme-realm");
    assertThat(accessClaims.get("iss")).isEqualTo("app");
    // jjwt coerces "aud" into a LinkedHashSet even when set as a plain map
    // claim (never a bare String at runtime) — see
    // InternalJwtRequestFilter's audience-matching fix for the consumer
    // side of this.
    @SuppressWarnings("unchecked")
    java.util.Collection<String> aud = (java.util.Collection<String>) accessClaims.get("aud");
    assertThat(aud).containsExactly("app-api");
    assertThat(accessClaims.get("code")).isEqualTo("jane.doe");
    assertThat(accessClaims.get("clientId")).isEqualTo("acme-client");
    assertThat(accessClaims.get("tokenType")).isEqualTo("access");
    assertThat(accessClaims.get("roles")).isEqualTo(List.of("TENANT_ADMIN"));
    assertThat(accessClaims.get("permissions")).isEqualTo(List.of("tenants.read.any"));
    assertThat(accessClaims.get("roleGroups")).isEqualTo(List.of("TENANT"));
    assertThat(accessClaims.getSubject()).isEqualTo(userId.toHexString());
  }

  @Test
  void mintTokenPairBuildsRefreshTokenWithReducedClaimsSharingTheSameJti() {
    stubLookups();

    TokenPair pair = internalTokenService.mintTokenPair(userId.toHexString(), tenantId.toHexString());
    Claims accessClaims = decode(pair.accessToken());
    Claims refreshClaims = decode(pair.refreshToken());

    assertThat(refreshClaims.get("tokenType")).isEqualTo("refresh");
    assertThat(refreshClaims.get("roles")).isNull();
    assertThat(refreshClaims.get("permissions")).isNull();
    assertThat(refreshClaims.get("roleGroups")).isNull();
    assertThat(refreshClaims.get("email")).isEqualTo("jane@example.com");
    assertThat(refreshClaims.getId()).isEqualTo(accessClaims.getId());
  }

  @Test
  void mintTokenPairThrowsWhenUserHasNoAssignmentToTheTenant() {
    ObjectId otherTenantId = new ObjectId();
    when(userQueryClient.getById(userId.toHexString())).thenReturn(user);
    when(tenantQueryClient.getById(otherTenantId.toHexString()))
        .thenReturn(TenantResponse.builder().id(otherTenantId.toHexString()).build());

    assertThatThrownBy(() -> internalTokenService.mintTokenPair(userId.toHexString(), otherTenantId.toHexString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no assignment");
  }

  @Test
  void mintTokenPairForKeycloakLoginResolvesUserAndTenantThenMints() {
    when(userQueryClient.getByEmail("jane@example.com")).thenReturn(user);
    when(tenantQueryClient.getAllByRealm("acme-realm")).thenReturn(List.of(tenant));
    stubLookups();

    TokenPair pair = internalTokenService.mintTokenPairForKeycloakLogin("jane@example.com", "acme-realm");

    assertThat(decode(pair.accessToken()).getSubject()).isEqualTo(userId.toHexString());
  }

  @Test
  void mintTokenPairForKeycloakLoginPicksTheOneTenantTheUserBelongsToAmongSeveralSharingTheRealm() {
    // A SUB_TENANT shares its MASTER_TENANT's realm — both come back from
    // getAllByRealm, but the user only has a party relationship to one.
    TenantResponse siblingTenant =
        TenantResponse.builder().id(new ObjectId().toHexString()).realm("acme-realm").clientId("sibling-client").build();
    when(userQueryClient.getByEmail("jane@example.com")).thenReturn(user);
    when(tenantQueryClient.getAllByRealm("acme-realm")).thenReturn(List.of(siblingTenant, tenant));
    stubLookups();

    TokenPair pair = internalTokenService.mintTokenPairForKeycloakLogin("jane@example.com", "acme-realm");

    assertThat(decode(pair.accessToken()).get("tenantId", String.class)).isEqualTo(tenantId.toHexString());
  }

  @Test
  void mintTokenPairForKeycloakLoginThrowsWhenUserHasNoAssignmentToAnyTenantInTheRealm() {
    TenantResponse unrelatedTenant =
        TenantResponse.builder().id(new ObjectId().toHexString()).realm("acme-realm").clientId("unrelated-client").build();
    when(userQueryClient.getByEmail("jane@example.com")).thenReturn(user);
    when(tenantQueryClient.getAllByRealm("acme-realm")).thenReturn(List.of(unrelatedTenant));

    assertThatThrownBy(() -> internalTokenService.mintTokenPairForKeycloakLogin("jane@example.com", "acme-realm"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("acme-realm");
  }

  @Test
  void mintTokenPairFromRefreshClaimsReusesTheOriginalTenantIdNotTheUsersPrimaryAssignment() {
    stubLookups();
    Claims refreshClaims = org.mockito.Mockito.mock(Claims.class);
    when(refreshClaims.getSubject()).thenReturn(userId.toHexString());
    when(refreshClaims.get("tenantId")).thenReturn(tenantId.toHexString());

    TokenPair pair = internalTokenService.mintTokenPair(refreshClaims);

    assertThat(decode(pair.accessToken()).get("tenantId")).isEqualTo(tenantId.toHexString());
  }

  @Test
  void verifyTokenReturnsClaimsForAValidUnrevokedAccessToken() {
    stubLookups();
    TokenPair pair = internalTokenService.mintTokenPair(userId.toHexString(), tenantId.toHexString());
    when(tokenBlacklistService.isRevoked(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

    Claims claims = internalTokenService.verifyToken(pair.accessToken());

    assertThat(claims.get("tokenType")).isEqualTo("access");
  }

  @Test
  void verifyTokenRejectsARefreshTokenPresentedAsAnAccessToken() {
    stubLookups();
    TokenPair pair = internalTokenService.mintTokenPair(userId.toHexString(), tenantId.toHexString());
    when(tokenBlacklistService.isRevoked(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

    assertThatThrownBy(() -> internalTokenService.verifyToken(pair.refreshToken()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected an access token");
  }

  @Test
  void verifyRefreshTokenRejectsAnAccessTokenPresentedAsARefreshToken() {
    stubLookups();
    TokenPair pair = internalTokenService.mintTokenPair(userId.toHexString(), tenantId.toHexString());
    when(tokenBlacklistService.isRevoked(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

    assertThatThrownBy(() -> internalTokenService.verifyRefreshToken(pair.accessToken()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected a refresh token");
  }

  @Test
  void verifyTokenRejectsARevokedToken() {
    stubLookups();
    TokenPair pair = internalTokenService.mintTokenPair(userId.toHexString(), tenantId.toHexString());
    when(tokenBlacklistService.isRevoked(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

    assertThatThrownBy(() -> internalTokenService.verifyToken(pair.accessToken()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("revoked");
  }

  @Test
  void verifyTokenRejectsAMalformedToken() {
    assertThatThrownBy(() -> internalTokenService.verifyToken("not-a-real-token"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid internal token");
  }

  @Test
  void revokeRegistersTheJtiWithItsExpiry() {
    stubLookups();
    TokenPair pair = internalTokenService.mintTokenPair(userId.toHexString(), tenantId.toHexString());
    String jti = decode(pair.accessToken()).getId();

    internalTokenService.revoke(pair.accessToken());

    verify(tokenBlacklistService).revoke(eq(jti), any(Long.class));
  }

  @Test
  void revokeIsANoOpForNullOrBlankToken() {
    internalTokenService.revoke(null);
    internalTokenService.revoke("   ");

    verify(tokenBlacklistService, never()).revoke(any(), any(Long.class));
  }

  @Test
  void revokeSilentlyIgnoresAGarbageToken() {
    internalTokenService.revoke("totally-not-a-jwt");

    verify(tokenBlacklistService, never()).revoke(any(), any(Long.class));
  }
}
