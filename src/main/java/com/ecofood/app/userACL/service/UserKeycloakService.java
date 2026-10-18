package com.ecofood.app.userACL.service;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around the Keycloak Admin REST API for user-level operations.
 * No password credential is ever set here — new users get requiredActions =
 * [VERIFY_EMAIL, UPDATE_PASSWORD] (see UserService.buildKeycloakUser) and
 * Keycloak's own verify-email link walks them through setting their own
 * password on first login.
 */
@Service
public class UserKeycloakService {

  /** Returns the newly created user's Keycloak-internal ID. */
  public String createUser(RealmResource realmResource, UserRepresentation user) {
    try (Response response = realmResource.users().create(user)) {
      if (response.getStatus() >= 300) {
        throw new IllegalStateException("Failed to create user, status=" + response.getStatus());
      }
      return CreatedResponseUtil.getCreatedId(response);
    }
  }

  public void sendVerifyEmail(RealmResource realmResource, String userId) {
    realmResource.users().get(userId).sendVerifyEmail();
  }
}
