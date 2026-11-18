package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CustomerKeycloakAccountServiceTest {
  private final Keycloak keycloak = mock(Keycloak.class, RETURNS_DEEP_STUBS);
  private final CustomerKeycloakAccountService service = new CustomerKeycloakAccountService(keycloak);

  @Test void requiresCurrentActiveVerifiedAccountAndSameSubjectAndEmail() {
    var user = new UserRepresentation(); user.setId("subject"); user.setEmail("CUSTOMER@example.test");
    user.setEnabled(true); user.setEmailVerified(true);
    when(keycloak.realm(CustomerIdentityServiceTest.REALM).users().get("subject").toRepresentation()).thenReturn(user);
    assertDoesNotThrow(() -> service.requireActiveVerified(CustomerIdentityServiceTest.portal(), "subject", "customer@example.test"));
    user.setEnabled(false);
    assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
        () -> service.requireActiveVerified(CustomerIdentityServiceTest.portal(), "subject", "customer@example.test")).getStatusCode());
    user.setEnabled(true); user.setEmailVerified(false);
    assertThrows(ResponseStatusException.class, () -> service.requireActiveVerified(CustomerIdentityServiceTest.portal(), "subject", "customer@example.test"));
    user.setEmailVerified(true); user.setId("other-subject");
    assertThrows(ResponseStatusException.class, () -> service.requireActiveVerified(CustomerIdentityServiceTest.portal(), "subject", "customer@example.test"));
  }

  @Test void distinguishesMissingAccountFromProviderOutageWithoutExposingAdminDetails() {
    var resource = keycloak.realm(CustomerIdentityServiceTest.REALM).users().get("subject");
    when(resource.toRepresentation()).thenThrow(new NotFoundException());
    assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
        () -> service.requireActiveVerified(CustomerIdentityServiceTest.portal(), "subject", "customer@example.test")).getStatusCode());
    doThrow(new IllegalStateException("private admin details")).when(resource).toRepresentation();
    var error = assertThrows(ResponseStatusException.class, () -> service.requireActiveVerified(CustomerIdentityServiceTest.portal(), "subject", "customer@example.test"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    assertFalse(error.getReason().contains("private"));
  }
}
