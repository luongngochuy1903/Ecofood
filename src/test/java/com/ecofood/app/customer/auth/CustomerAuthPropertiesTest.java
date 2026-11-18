package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CustomerAuthPropertiesTest {
  @Test void requiresConfiguredTenantIdAndSafeKeycloakServerUrl() {
    var properties = CustomerIdentityServiceTest.properties();
    assertDoesNotThrow(properties::requireConfigured);
    properties.setEnabled(false);
    assertThrows(ResponseStatusException.class, properties::requireConfigured);
    properties.setEnabled(true);
    for (String id : java.util.List.of("", "CustomerPortal", "invalid-id")) {
      properties.setTenantId(id); assertThrows(ResponseStatusException.class, properties::requireConfigured);
    }
    properties.setTenantId(CustomerIdentityServiceTest.TENANT);
    for (String url : java.util.List.of("http://external.example", "https://user:password@idp.test", "https://idp.test?token=x")) {
      properties.setServerUrl(url); assertThrows(ResponseStatusException.class, properties::requireConfigured);
    }
  }
}
