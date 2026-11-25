package com.ecofood.app.auth.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(KeycloakTokenVerifier.class);

  private static final String JWKS_PATH = "/protocol/openid-connect/certs";

  @Value("${jwt.trustedIssuerPrefix}")
  private String trustedIssuerPrefix;

  private final ConcurrentMap<String, DefaultJWTProcessor<SecurityContext>> processors =
      new ConcurrentHashMap<>();
  // Parallel cache so warmUp() can force a JWKS fetch directly — the
  // processor itself (above) doesn't expose its JWKSource once built.
  private final ConcurrentMap<String, JWKSource<SecurityContext>> jwkSources =
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

  /**
   * Forces a JWKS fetch for a just-onboarded tenant's realm, during
   * onboarding's own (already slow) request, so the realm's very first
   * /auth/exchange call afterward hits a warm in-memory cache instead of
   * paying the cold-fetch network round-trip itself. Best-effort: a failure
   * here only means the next exchange call falls back to its own on-demand
   * fetch (now with the longer timeout below) — it must never fail
   * onboarding itself.
   */
  public void warmUp(String realmName) {
    String issuer = trustedIssuerPrefix + realmName;
    try {
      processors.computeIfAbsent(issuer, this::buildProcessor);
      JWKSource<SecurityContext> jwkSource = jwkSources.get(issuer);
      jwkSource.get(new JWKSelector(new JWKMatcher.Builder().build()), null);
    } catch (Exception e) {
      log.warn("JWKS warm-up failed for issuer {} — exchange will fall back to an on-demand fetch", issuer, e);
    }
  }

  private DefaultJWTProcessor<SecurityContext> buildProcessor(String issuer) {
    URL jwksUrl;
    try {
      jwksUrl = new URL(issuer + JWKS_PATH);
    } catch (MalformedURLException e) {
      throw new IllegalStateException("Malformed JWKS URL for issuer " + issuer, e);
    }

    // Bumped from Nimbus's original too-aggressive 5s (itself already a
    // bump from the sub-second default) — a real network round-trip through
    // Cloudflare's proxy to the VM's Keycloak, especially the very first
    // (cold-cache) fetch right after a tenant's realm was just created
    // during onboarding, can occasionally exceed 5s under normal latency.
    // warmUp() above is the real fix for the common case; this is the
    // safety margin for whatever still hits a cold fetch directly.
    DefaultResourceRetriever resourceRetriever = new DefaultResourceRetriever(10000, 10000);
    JWKSource<SecurityContext> jwkSource =
        JWKSourceBuilder.<SecurityContext>create(jwksUrl, resourceRetriever).cache(true).build();
    jwkSources.put(issuer, jwkSource);

    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
    processor.setJWTClaimsSetVerifier(
        new DefaultJWTClaimsVerifier<>(new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("exp")));

    return processor;
  }
}
