package com.ecofood.app.customer.auth;

import jakarta.ws.rs.NotFoundException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.Keycloak;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CustomerKeycloakAccountService {
  private final Keycloak keycloak;

  public void requireActiveVerified(CustomerTenantResolver.Portal portal, String subject, String email) {
    try {
      var user = keycloak.realm(portal.realm()).users().get(subject).toRepresentation();
      if (!subject.equals(user.getId()) || !Boolean.TRUE.equals(user.isEnabled())
          || !Boolean.TRUE.equals(user.isEmailVerified()) || user.getEmail() == null
          || !email.equals(user.getEmail().strip().toLowerCase(Locale.ROOT))) throw rejected();
    } catch (NotFoundException ex) {
      throw rejected();
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      // A Keycloak outage must not disable identity checks or expose Admin API response details.
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Customer identity provider unavailable");
    }
  }

  private ResponseStatusException rejected() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer account unavailable or email not verified");
  }
}
