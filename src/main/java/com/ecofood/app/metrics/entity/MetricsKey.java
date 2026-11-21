package com.ecofood.app.metrics.entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Currency;

public record MetricsKey(String propositionId, String tenantId, int year, int month, String currency) {
  public MetricsKey {
    if (propositionId == null || propositionId.isBlank() || tenantId == null || tenantId.isBlank()
        || currency == null || !currency.matches("[A-Z]{3}") || month < 1 || month > 12
        || year < 1 || year > 9999) throw new IllegalArgumentException("Invalid metrics key");
    if (Currency.getInstance(currency).getDefaultFractionDigits() < 0)
      throw new IllegalArgumentException("Unsupported metrics currency");
  }

  public String id() {
    String value = propositionId.length() + ":" + propositionId + tenantId.length() + ":" + tenantId
        + ":" + year + ":" + month + ":" + currency;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
  }
}
