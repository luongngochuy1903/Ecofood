package com.ecofood.app.product.service;

import java.util.Currency;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ProductCurrency {
  private ProductCurrency() {}

  static String normalize(String value) {
    if (value == null || value.isBlank()) return "VND";
    String code = value.trim().toUpperCase(Locale.ROOT);
    try {
      Currency.getInstance(code);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a valid ISO 4217 currency");
    }
    return code;
  }
}
