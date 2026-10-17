package com.ecofood.app.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Mints REAL signed JWTs with jjwt (same library AuthenticationContext itself
 * uses to verify) rather than mocking — getClaims() re-parses the token from
 * scratch every call, so a real signature is required for any of these
 * accessors to return anything.
 */
class AuthenticationContextTest {

  // 32+ raw bytes, base64-encoded — matches jwt.secret's documented >=32-byte
  // requirement for HS256.
  private static final String SECRET =
      java.util.Base64.getEncoder().encodeToString("unit-test-only-hmac-secret-0123456789".getBytes());

  @AfterEach
  void unload() {
    AuthenticationContext.unload();
  }

  private void authenticateAs(String userId, String tenantId, Map<String, Object> extraClaims) {
    SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    var builder =
        Jwts.builder()
            .subject(userId)
            .claim("tenantId", tenantId)
            .claim("email", "jane@example.com")
            .claim("code", "jane.doe")
            .claim("clientId", "some-client")
            .claim("tenantCode", "acme-realm")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000));
    extraClaims.forEach(builder::claim);
    String token = builder.signWith(key).compact();

    AuthenticationContext.setToken(token);
    AuthenticationContext.setSecret(SECRET);
  }

  @Test
  void getTokenStripsBearerPrefix() {
    authenticateAs(new ObjectId().toHexString(), new ObjectId().toHexString(), Map.of());
    String full = AuthenticationContext.getFullToken();
    AuthenticationContext.setToken("Bearer " + full);

    assertThat(AuthenticationContext.getToken()).isEqualTo(full);
  }

  @Test
  void getTokenReturnsNullWhenNoneSet() {
    assertThat(AuthenticationContext.getToken()).isNull();
  }

  @Test
  void resolvesUserAndTenantIdsFromClaims() {
    ObjectId userId = new ObjectId();
    ObjectId tenantId = new ObjectId();
    authenticateAs(userId.toHexString(), tenantId.toHexString(), Map.of());

    assertThat(AuthenticationContext.getUserId()).isEqualTo(userId);
    assertThat(AuthenticationContext.getTenantId()).isEqualTo(tenantId);
  }

  @Test
  void resolvesUserNameCodeClientIdAndTenantCode() {
    authenticateAs(new ObjectId().toHexString(), new ObjectId().toHexString(), Map.of());

    assertThat(AuthenticationContext.getUserName()).isEqualTo("jane@example.com");
    assertThat(AuthenticationContext.getUserCode()).isEqualTo("jane.doe");
    assertThat(AuthenticationContext.getClientId()).isEqualTo("some-client");
    assertThat(AuthenticationContext.getTenantCode()).isEqualTo("acme-realm");
  }

  @Test
  void resolvesRoleGroupsRolesAndPermissionsLists() {
    authenticateAs(
        new ObjectId().toHexString(),
        new ObjectId().toHexString(),
        Map.of(
            "roleGroups", List.of("TENANT", "MASTER_TENANT"),
            "roles", List.of("TENANT_ADMIN"),
            "permissions", List.of("tenants.read.any")));

    assertThat(AuthenticationContext.getRoleGroups()).containsExactly("TENANT", "MASTER_TENANT");
    assertThat(AuthenticationContext.getRoles()).containsExactly("TENANT_ADMIN");
    assertThat(AuthenticationContext.getPermissions()).containsExactly("tenants.read.any");
  }

  @Test
  void returnsNullRoleGroupsWhenClaimAbsent() {
    authenticateAs(new ObjectId().toHexString(), new ObjectId().toHexString(), Map.of());

    assertThat(AuthenticationContext.getRoleGroups()).isNull();
  }

  @Test
  void clientIpAddressIsIndependentOfTokenClaims() {
    AuthenticationContext.setClientIpAddress("203.0.113.5");
    assertThat(AuthenticationContext.getClientIpAddress()).isEqualTo("203.0.113.5");
  }

  @Test
  void unloadClearsTokenButNotClientIpAddress() {
    authenticateAs(new ObjectId().toHexString(), new ObjectId().toHexString(), Map.of());
    AuthenticationContext.setClientIpAddress("203.0.113.5");

    AuthenticationContext.unload();

    assertThat(AuthenticationContext.getToken()).isNull();
    // Matches the real implementation: unload() only clears token/secret.
    assertThat(AuthenticationContext.getClientIpAddress()).isEqualTo("203.0.113.5");
  }
}
