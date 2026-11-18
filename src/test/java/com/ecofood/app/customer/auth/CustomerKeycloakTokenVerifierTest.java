package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import com.ecofood.app.auth.service.KeycloakTokenVerifier;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.jupiter.api.Test;

class CustomerKeycloakTokenVerifierTest {
  @Test void exactConfiguredIssuerUsesRealRs256SignatureAndStillRejectsExpiredOrForgedTokens() throws Exception {
    var key = new RSAKeyGenerator(2048).keyID("customer-test").generate();
    var other = new RSAKeyGenerator(2048).keyID("customer-test").generate();
    byte[] bytes = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/ecofood-customers";
    server.createContext("/realms/ecofood-customers/protocol/openid-connect/certs", exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    server.start();
    try {
      var verifier = new KeycloakTokenVerifier();
      assertEquals(issuer, verifier.verifyForIssuer(token(key, issuer, 60_000), issuer).getIssuer());
      assertThrows(IllegalArgumentException.class, () -> verifier.verifyForIssuer(token(other, issuer, 60_000), issuer));
      assertThrows(IllegalArgumentException.class, () -> verifier.verifyForIssuer(token(key, issuer, -120_000), issuer));
      assertThrows(IllegalArgumentException.class, () -> verifier.verifyForIssuer(token(key, issuer + "-attacker", 60_000), issuer));
    } finally { server.stop(0); }
  }
  private String token(RSAKey key, String issuer, long expiresIn) throws Exception {
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("customer-test").build(),
        new JWTClaimsSet.Builder().issuer(issuer).subject("keycloak-subject")
            .expirationTime(new Date(System.currentTimeMillis() + expiresIn)).build());
    jwt.sign(new RSASSASigner(key)); return jwt.serialize();
  }
}
