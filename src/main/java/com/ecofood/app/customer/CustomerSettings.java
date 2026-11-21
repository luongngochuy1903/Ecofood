package com.ecofood.app.customer;

import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CustomerSettings {
  private final String registrationTenants, receiptSecret, publicBase;
  public final int receiptHours;
  public CustomerSettings(@Value("${ecofood.customer.registration-tenants:}") String tenants,
      @Value("${ecofood.customer.receipt-secret:}") String secret,
      @Value("${ecofood.customer.public-base-url:}") String base,
      @Value("${ecofood.customer.receipt-hours:24}") int hours) {
    registrationTenants = tenants; receiptSecret = secret; publicBase = base; receiptHours = Math.max(1, Math.min(168, hours));
  }
  public boolean registrationAllowed(String tenant) { return Arrays.stream(registrationTenants.split(",")).map(String::trim).anyMatch(tenant::equals); }
  public byte[] receiptKey() {
    try { byte[] key = Base64.getDecoder().decode(receiptSecret); if (key.length >= 32) return key; }
    catch (IllegalArgumentException ignored) { }
    throw unavailable("Configure CUSTOMER_RECEIPT_SECRET with at least 32 random bytes encoded as Base64");
  }
  public String base() {
    try {
      URI uri = URI.create(publicBase);
      boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || (uri.getHost() != null && uri.getHost().endsWith(".localhost"));
      if (uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null && publicBase.length() <= 500
          && (uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"))
          && ("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) return publicBase.replaceAll("/+$", "");
    } catch (IllegalArgumentException ignored) { }
    throw unavailable("Configure CUSTOMER_PUBLIC_BASE_URL with the trusted frontend origin");
  }
  static ResponseStatusException unavailable(String message) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message); }
}
