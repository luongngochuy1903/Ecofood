package com.ecofood.app.auth.service;

import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.client.CoreUserAccessClient;
import com.ecofood.core.client.CoreUserQueryClient;
import com.ecofood.core.dto.PartyRelationshipResponse;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.dto.UserQueryResponse;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Mints and validates this app's own internal access+refresh token pair —
 * same shape as DCMP's real Service_Core JwtTokenUtil, confirmed by reading
 * that class directly:
 *  - jjwt + HS*, key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))
 *  - ONE shared jti across the access+refresh pair (not two independent ids)
 *  - "iss"/"aud" are plain custom claims (jjwt's registered issuer/audience
 *    are never set) — read via claims.get("iss")/claims.get("aud"), not
 *    claims.getIssuer()/getAudience()
 *  - the refresh token carries a REDUCED claim set: no roles/permissions/
 *    roleGroups, everything else (email/tenantId/tenantCode/iss/aud/code/
 *    clientId) is identical to the access token
 *
 * Deliberate gaps vs. DCMP's real claim set — there is no tenant/user schema
 * rich enough to source everything DCMP does:
 *  - roles/permissions/roleGroups ARE now all real, resolved via
 *    UserAccessService from this project's own ACL module
 *    (UserRole/RolePermission/GroupRole) for the specific tenant being
 *    logged into — a user with no UserRole for that tenant simply gets
 *    empty lists, not an error. roleGroups matches DCMP's real computation
 *    exactly: transitive via role-id overlap with GroupRole, never a direct
 *    user-to-group link (confirmed by reading Service_SecurityManagement's
 *    UserRoleUtils.createTenantRoleResult directly).
 *  - location/userCategories/customerCategories are omitted entirely rather
 *    than fabricated — the underlying tenant/user entities have no
 *    corresponding fields.
 *  - tenantCode is mapped from TenantResponse.realm (our closest stable
 *    tenant-slug concept) and "code" from UserQueryResponse.username — DCMP
 *    has dedicated fields for both that this project doesn't.
 *  - a tokenType claim is kept (DCMP's real tokens have none — DCMP instead
 *    cross-checks a refresh token's jti against its paired access token's
 *    jti in validateRefreshToken) because this app's already-built refresh
 *    flow sends only the bare refresh token on silent refresh, not the
 *    paired access token DCMP's model requires; without a self-describing
 *    marker, an access token could otherwise be replayed at the refresh
 *    endpoint.
 */
@Service
@RequiredArgsConstructor
public class InternalTokenService {

  private static final String CLAIM_TOKEN_TYPE = "tokenType";
  private static final String TOKEN_TYPE_ACCESS = "access";
  private static final String TOKEN_TYPE_REFRESH = "refresh";

  private static final String CLAIM_EMAIL = "email";
  private static final String CLAIM_TENANT_ID = "tenantId";
  private static final String CLAIM_TENANT_CODE = "tenantCode";
  private static final String CLAIM_ISS = "iss";
  private static final String CLAIM_AUD = "aud";
  private static final String CLAIM_CODE = "code";
  private static final String CLAIM_CLIENT_ID = "clientId";
  private static final String CLAIM_ROLES = "roles";
  private static final String CLAIM_PERMISSIONS = "permissions";
  private static final String CLAIM_ROLE_GROUPS = "roleGroups";

  @Value("${jwt.secret}")
  private String secret;

  @Value("${jwt.tokenValidityInSeconds}")
  private long tokenValidityInSeconds;

  @Value("${jwt.refreshTokenValidityInSeconds}")
  private long refreshTokenValidityInSeconds;

  @Value("${jwt.issuer}")
  private String issuer;

  @Value("${jwt.audience}")
  private String audience;

  private final CoreTenantQueryClient tenantQueryClient;
  private final CoreUserQueryClient userQueryClient;
  private final CoreUserAccessClient userAccessClient;
  private final TokenBlacklistService tokenBlacklistService;

  /** A matched access+refresh pair sharing one jti — same pairing DCMP's real JwtTokenUtil uses. */
  public record TokenPair(String accessToken, String refreshToken) {}

  /**
   * Resolves the local user/tenant by the Keycloak login's own identifying
   * claims (preferred_username, issuing realm) and mints a token pair — the
   * repository lookups AuthController used to do itself, moved here so the
   * controller stays a thin HTTP-mapping layer.
   */
  public TokenPair mintTokenPairForKeycloakLogin(String username, String realm) {
    UserQueryResponse user = userQueryClient.getByUsername(username);
    TenantResponse tenant = tenantQueryClient.getByRealm(realm);
    return mintTokenPair(user.getId(), tenant.getId());
  }

  /**
   * Mints a token pair scoped to ONE specific tenant assignment — the tenant
   * whose Keycloak realm the login actually authenticated against (see
   * AuthController, which resolves this from the Keycloak token's issuer),
   * not just the user's "primary"/first assignment. Fails if the user has
   * no assignment to that tenant at all — i.e. they're not actually a
   * member of the realm they just logged into.
   */
  public TokenPair mintTokenPair(String userId, String tenantId) {
    UserQueryResponse user = userQueryClient.getById(userId);
    TenantResponse tenant = tenantQueryClient.getById(tenantId);
    PartyRelationshipResponse relation =
        user.getPartyRelationships().stream()
            .filter(r -> tenantId.equals(r.getToPartyId()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("User " + userId + " has no assignment to tenant " + tenantId));

    String jti = UUID.randomUUID().toString();
    Map<String, Object> baseClaims = buildBaseClaims(user, tenant);

    UserAccessClaims access = userAccessClient.resolveAccess(new ObjectId(user.getId()), new ObjectId(tenant.getId()));

    Map<String, Object> accessClaims = new HashMap<>(baseClaims);
    accessClaims.put(CLAIM_ROLES, access.roles());
    accessClaims.put(CLAIM_PERMISSIONS, access.permissions());
    accessClaims.put(CLAIM_ROLE_GROUPS, access.roleGroups());
    accessClaims.put(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS);

    Map<String, Object> refreshClaims = new HashMap<>(baseClaims);
    refreshClaims.put(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH);

    String accessToken = sign(jti, user.getId(), accessClaims, tokenValidityInSeconds);
    String refreshToken = sign(jti, user.getId(), refreshClaims, refreshTokenValidityInSeconds);
    return new TokenPair(accessToken, refreshToken);
  }

  /**
   * Re-mints a fresh pair for an already-verified refresh token — reads the
   * ORIGINAL tenantId claim off that token so a silent refresh stays scoped
   * to the same tenant the session was minted for, not re-derived from the
   * user's primary assignment.
   */
  public TokenPair mintTokenPair(Claims refreshClaims) {
    String tenantId = (String) refreshClaims.get(CLAIM_TENANT_ID);
    return mintTokenPair(refreshClaims.getSubject(), tenantId);
  }

  private Map<String, Object> buildBaseClaims(UserQueryResponse user, TenantResponse tenant) {
    Map<String, Object> claims = new HashMap<>();
    claims.put(CLAIM_EMAIL, user.getEmail());
    claims.put(CLAIM_TENANT_ID, tenant.getId());
    claims.put(CLAIM_TENANT_CODE, tenant.getRealm());
    claims.put(CLAIM_ISS, issuer);
    claims.put(CLAIM_AUD, audience);
    claims.put(CLAIM_CODE, user.getUsername());
    claims.put(CLAIM_CLIENT_ID, tenant.getClientId());
    return claims;
  }

  private String sign(String jti, String subject, Map<String, Object> claims, long validitySeconds) {
    long now = System.currentTimeMillis();
    return Jwts.builder()
        .claims(claims)
        .id(jti)
        .subject(subject)
        .issuedAt(new Date(now))
        .expiration(new Date(now + validitySeconds * 1000))
        .signWith(signingKey())
        .compact();
  }

  /**
   * Validates an ACCESS token — signature, expiry, revocation, and that it's
   * actually an access token. Called on every request to a protected
   * resource (InternalJwtRequestFilter); Keycloak is never consulted here.
   */
  public Claims verifyToken(String token) {
    Claims claims = verifySignatureAndRevocation(token);
    if (!TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE))) {
      throw new IllegalArgumentException("Expected an access token, got tokenType=" + claims.get(CLAIM_TOKEN_TYPE));
    }
    return claims;
  }

  /** Validates a REFRESH token specifically — same checks, requires tokenType=refresh. */
  public Claims verifyRefreshToken(String token) {
    Claims claims = verifySignatureAndRevocation(token);
    if (!TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TOKEN_TYPE))) {
      throw new IllegalArgumentException("Expected a refresh token, got tokenType=" + claims.get(CLAIM_TOKEN_TYPE));
    }
    return claims;
  }

  private Claims verifySignatureAndRevocation(String token) {
    Claims claims;
    try {
      claims = Jwts.parser().verifyWith(signingKey()).build().parseSignedClaims(token).getPayload();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid internal token", e);
    }

    if (tokenBlacklistService.isRevoked(claims.getId())) {
      throw new IllegalArgumentException("Internal token has been revoked");
    }
    return claims;
  }

  /**
   * Server-side revocation on logout: verifies the token's signature (not
   * its expiry — a token on the verge of expiring should still be
   * revocable) and registers its jti with the blacklist until its natural
   * exp. Silently no-ops on a malformed/already-garbage token — logout
   * should never fail just because one of the two tokens it's handed is junk.
   */
  public void revoke(String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    try {
      Claims claims = Jwts.parser().verifyWith(signingKey()).build().parseSignedClaims(token).getPayload();
      Date expiration = claims.getExpiration();
      long expiryEpochSeconds =
          expiration != null ? expiration.toInstant().getEpochSecond() : Instant.now().getEpochSecond();
      tokenBlacklistService.revoke(claims.getId(), expiryEpochSeconds);
    } catch (Exception ignored) {
      // Not a validly-signed token from us — nothing meaningful to revoke.
    }
  }

  private SecretKey signingKey() {
    return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
  }
}
