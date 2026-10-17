package com.ecofood.app.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers the two early-exit branches that don't require a live JWKS
 * endpoint: unparsable tokens and untrusted issuers. The actual RS256
 * signature-verification path (buildProcessor's JWKSource) fetches from a
 * real network URL and is intentionally out of scope for a unit test — that
 * belongs in an integration test against a real/mocked Keycloak.
 */
class KeycloakTokenVerifierTest {

  private static final String TRUSTED_PREFIX = "http://localhost:8080/realms/";

  private KeycloakTokenVerifier verifier;

  @BeforeEach
  void setUp() {
    verifier = new KeycloakTokenVerifier();
    ReflectionTestUtils.setField(verifier, "trustedIssuerPrefix", TRUSTED_PREFIX);
  }

  private String signedTokenWithIssuer(String issuer) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder().issuer(issuer).expirationTime(new Date(System.currentTimeMillis() + 60_000)).build();
    SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    // Signature doesn't need to verify for the untrusted-issuer check — that
    // rejection happens before any signature processing — just needs to be
    // syntactically well-formed enough for SignedJWT.parse() to succeed.
    signedJWT.sign(new MACSigner("unit-test-only-hmac-secret-0123456789012345"));
    return signedJWT.serialize();
  }

  @Test
  void rejectsAnUnparsableToken() {
    assertThatThrownBy(() -> verifier.verify("not-a-jwt-at-all"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unparsable");
  }

  @Test
  void rejectsAnIssuerOutsideTheTrustedPrefix() throws Exception {
    String token = signedTokenWithIssuer("http://evil.example.com/realms/fake-tenant");

    assertThatThrownBy(() -> verifier.verify(token))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Untrusted token issuer");
  }

  @Test
  void rejectsATokenWithNoIssuerClaimAtAll() throws Exception {
    String token = signedTokenWithIssuer(null);

    assertThatThrownBy(() -> verifier.verify(token))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Untrusted token issuer");
  }

  @Test
  void extractRealmPullsTheLastPathSegmentFromTheIssuerUrl() {
    assertThat(KeycloakTokenVerifier.extractRealm("http://localhost:8080/realms/acme-tenant"))
        .isEqualTo("acme-tenant");
  }
}
