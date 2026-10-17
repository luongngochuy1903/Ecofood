package com.ecofood.app.auth.controller;

import com.ecofood.app.auth.dto.ExchangeTokenRequest;
import com.ecofood.app.auth.dto.ExchangeTokenResponse;
import com.ecofood.app.auth.dto.LogoutRequest;
import com.ecofood.app.auth.dto.RefreshTokenRequest;
import com.ecofood.app.auth.service.InternalTokenService;
import com.ecofood.app.auth.service.InternalTokenService.TokenPair;
import com.ecofood.app.auth.service.KeycloakTokenVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies the Keycloak token the frontend already obtained (its own
 * browser <-> Keycloak PKCE flow — this endpoint plays no part in that),
 * resolves it back to our own user by Keycloak's standard preferred_username
 * claim (same resolution-by-username pattern DCMP's real exchangeToken uses
 * — it looks the user up via its SecMgt ACL service, not by trusting any
 * custom id claim embedded in the Keycloak token), then mints this app's
 * own internal access+refresh token pair (see InternalTokenService for the
 * exact DCMP-matched claim shape).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final KeycloakTokenVerifier keycloakTokenVerifier;
  private final InternalTokenService internalTokenService;

  @PostMapping("/exchange")
  public ResponseEntity<?> exchange(@Valid @RequestBody ExchangeTokenRequest request) {
    JWTClaimsSet keycloakClaims;
    try {
      keycloakClaims = keycloakTokenVerifier.verify(request.getAccessToken());
    } catch (Exception e) {
      log.warn("Rejecting exchange with 401: Keycloak token verification failed", e);
      return unauthorized();
    }

    Object usernameClaim = keycloakClaims.getClaims().get("preferred_username");
    String username = usernameClaim != null ? usernameClaim.toString() : null;
    if (username == null || username.isBlank()) {
      log.warn("Rejecting exchange with 401: Keycloak token has no preferred_username claim");
      return unauthorized();
    }

    // Resolve the tenant from the realm that actually issued this Keycloak
    // token — NOT the user's "primary" tenant assignment — so a user
    // assigned to multiple tenants gets a JWT scoped to the one they
    // actually logged into. Lookups live in InternalTokenService, not here.
    String realm = KeycloakTokenVerifier.extractRealm(keycloakClaims.getIssuer());

    TokenPair tokenPair;
    try {
      tokenPair = internalTokenService.mintTokenPairForKeycloakLogin(username, realm);
    } catch (IllegalArgumentException e) {
      log.warn("Rejecting exchange with 401: {}", e.getMessage());
      return unauthorized();
    }

    return ResponseEntity.ok(
        new ExchangeTokenResponse(tokenPair.accessToken(), tokenPair.refreshToken(), keycloakClaims.getClaims()));
  }

  @PostMapping("/refresh-token")
  public ResponseEntity<?> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
    Claims claims;
    try {
      claims = internalTokenService.verifyRefreshToken(request.getRefreshToken());
    } catch (Exception e) {
      log.warn("Rejecting refresh with 401: invalid/expired/wrong-type refresh token", e);
      return unauthorized();
    }

    TokenPair tokenPair = internalTokenService.mintTokenPair(claims);
    return ResponseEntity.ok(new ExchangeTokenResponse(tokenPair.accessToken(), tokenPair.refreshToken(), null));
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(@RequestBody LogoutRequest request) {
    internalTokenService.revoke(request.getAccessToken());
    internalTokenService.revoke(request.getRefreshToken());
    return ResponseEntity.ok(Map.of("message", "Logged out"));
  }

  private ResponseEntity<Map<String, String>> unauthorized() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("message", "Unauthorized"));
  }
}
