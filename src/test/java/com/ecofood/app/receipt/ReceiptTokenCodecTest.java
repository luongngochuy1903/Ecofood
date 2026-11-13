package com.ecofood.app.receipt;

import com.ecofood.app.receipt.config.ReceiptProperties;
import com.ecofood.app.receipt.service.ReceiptTokenCodec;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptTokenCodecTest {
  private final ReceiptProperties properties = new ReceiptProperties();
  private final ReceiptTokenCodec codec = new ReceiptTokenCodec(properties);

  @Test void stableOpaqueTokenAndSha256Digest() {
    properties.setTokenSecret(Base64.getEncoder().encodeToString(new byte[32]));
    String token = codec.token("receipt-a");
    assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
    assertEquals(token, codec.token("receipt-a"));
    assertTrue(codec.hash(token).matches("[a-f0-9]{64}"));
    assertTrue(codec.matches(token, codec.hash(token)));
    assertFalse(codec.matches(token, codec.hash("other")));
    assertNotEquals(token, codec.token("receipt-b"));
  }

  @Test void scopesAndRequestIdsCannotCollideThroughConcatenation() {
    assertNotEquals(codec.receiptId("ab", "c", "invoice", "request"), codec.receiptId("a", "bc", "invoice", "request"));
    assertNotEquals(codec.receiptId("tenant-a", "pos", "invoice", "request"), codec.receiptId("tenant-b", "pos", "invoice", "request"));
    assertNotEquals(codec.receiptId("tenant", "pos-a", "invoice", "request"), codec.receiptId("tenant", "pos-b", "invoice", "request"));
    assertNotEquals(codec.receiptId("tenant", "pos", "invoice-a", "request"), codec.receiptId("tenant", "pos", "invoice-b", "request"));
    assertNotEquals(codec.receiptId("tenant", "pos", "invoice", "request-a"), codec.receiptId("tenant", "pos", "invoice", "request-b"));
  }

  @Test void rejectsMissingInvalidOrShortSecretWithoutFallbackKey() {
    for (String secret : Arrays.asList(null, "", "not-base64", Base64.getEncoder().encodeToString(new byte[16]))) {
      properties.setTokenSecret(secret);
      assertEquals(503, assertThrows(ResponseStatusException.class, () -> codec.token("receipt")).getStatusCode().value());
    }
  }

  @Test void rotatingKeyChangesTokenWithoutMakingStoredHashAUsableCredential() {
    properties.setTokenSecret(Base64.getEncoder().encodeToString(new byte[32]));
    String before = codec.token("receipt");
    byte[] rotated = new byte[32]; Arrays.fill(rotated, (byte) 1);
    properties.setTokenSecret(Base64.getEncoder().encodeToString(rotated));
    assertNotEquals(before, codec.token("receipt"));
    assertFalse(codec.matches(codec.hash(before), codec.hash(before)));
  }
}
