package com.ecofood.app.customer.auth;

import java.net.URI;
import org.bson.types.ObjectId;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ecofood.customer-auth")
public class CustomerAuthProperties {
  private boolean enabled = true;
  private String tenantId;
  private String serverUrl;

  public void requireConfigured() {
    if (!enabled) throw unavailable();
    try {
      URI uri = URI.create(serverUrl);
      if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
          || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
              && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))
          || tenantId == null || !ObjectId.isValid(tenantId))
        throw unavailable();
    } catch (IllegalArgumentException | NullPointerException ex) { throw unavailable(); }
  }

  private org.springframework.web.server.ResponseStatusException unavailable() {
    return new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Customer authentication is not configured");
  }
}
