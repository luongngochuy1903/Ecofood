package com.ecofood.app.receipt.service;

import com.ecofood.app.receipt.config.ReceiptProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class ReceiptTokenCodec {
  private final ReceiptProperties properties;

  public String receiptId(String tenantId, String posId, String invoiceId, String requestId) {
    // Length-prefix each field to prevent ambiguous concatenation.
    return hash("receipt-id:v1:" + field(tenantId) + field(posId) + field(invoiceId) + field(requestId));
  }

  public String token(String receiptId) {
    byte[] key;
    try { key = Base64.getDecoder().decode(properties.getTokenSecret()); }
    catch (IllegalArgumentException | NullPointerException ex) { throw unavailable(); }
    if (key.length < 32) throw unavailable();
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          mac.doFinal(("receipt-token:v1:" + receiptId).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) { throw new IllegalStateException("Receipt token generation failed"); }
  }

  public String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) { throw new IllegalStateException("Receipt hashing unavailable"); }
  }

  public boolean matches(String token, String expectedHash) {
    return expectedHash != null && MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
        expectedHash.getBytes(StandardCharsets.US_ASCII));
  }

  private String field(String value) { return value.length() + ":" + value; }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt token secret is not configured correctly");
  }
}
