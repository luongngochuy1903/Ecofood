package com.ecofood.app.auth.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Verifies a Keycloak-issued access token before it's trusted for exchange.
 * Mirrors DCMP's real approach: check the issuer against a trusted allowlist
 * first (to stop an attacker pointing "iss" at their own Keycloak), then
 * verify the RS256 signature against that issuer's own JWKS. DCMP uses
 * Nimbus for this side specifically — its own outgoing tokens are signed
 * with jjwt/HS256 instead (see InternalTokenService).
 *
 * Simplified vs. DCMP: a single trusted-issuer PREFIX instead of a
 * configurable list — fine for one local Keycloak, not production-shaped.
 */
@Service
public class KeycloakTokenVerifier {

  private static final String JWKS_PATH = "/protocol/openid-connect/certs";

  @Value("${jwt.trustedIssuerPrefix}")
  private String trustedIssuerPrefix;

  private final ConcurrentMap<String, DefaultJWTProcessor<SecurityContext>> processors =
      new ConcurrentHashMap<>();

  public JWTClaimsSet verify(String accessToken) {
    SignedJWT signedJWT;
    String issuer;
    try {
      signedJWT = SignedJWT.parse(accessToken);
      issuer = signedJWT.getJWTClaimsSet().getIssuer();
    } catch (ParseException e) {
      throw new IllegalArgumentException("Unparsable access token", e);
    }

    if (issuer == null || !issuer.startsWith(trustedIssuerPrefix)) {
      throw new IllegalArgumentException("Untrusted token issuer: " + issuer);
    }

    DefaultJWTProcessor<SecurityContext> processor = processors.computeIfAbsent(issuer, this::buildProcessor);

    try {
      return processor.process(signedJWT, null);
    } catch (Exception e) {
      throw new IllegalArgumentException("Access token signature/claims verification failed", e);
    }
  }

  /**
   * Extracts the realm name from a Keycloak issuer URL
   * (".../realms/{realmName}") — used to resolve which tenant a login
   * actually authenticated against, instead of assuming a user's "primary"
   * tenant assignment.
   */
  public static String extractRealm(String issuer) {
    return issuer.substring(issuer.lastIndexOf('/') + 1);
  }

  private DefaultJWTProcessor<SecurityContext> buildProcessor(String issuer) {
    URL jwksUrl;
    try {
      jwksUrl = new URL(issuer + JWKS_PATH);
    } catch (MalformedURLException e) {
      throw new IllegalStateException("Malformed JWKS URL for issuer " + issuer, e);
    }

    JWKSource<SecurityContext> jwkSource = JWKSourceBuilder.<SecurityContext>create(jwksUrl).cache(true).build();

    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
    processor.setJWTClaimsSetVerifier(
        new DefaultJWTClaimsVerifier<>(new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("exp")));

    return processor;
  }
}
