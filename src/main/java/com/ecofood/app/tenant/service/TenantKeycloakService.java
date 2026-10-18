package com.ecofood.app.tenant.service;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.representations.userprofile.config.UPConfig.UnmanagedAttributePolicy;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around the Keycloak Admin REST API for realm/client-level
 * operations. Knows nothing about tenant business rules — only how to
 * create a realm/client given an already-built representation; TenantService
 * owns deciding what values go into those representations.
 */
@Service
@RequiredArgsConstructor
public class TenantKeycloakService {

  private final Keycloak keycloakAdminClient;

  public void createRealm(RealmRepresentation realm) {
    keycloakAdminClient.realms().create(realm);
  }

  public RealmResource realm(String realmName) {
    return keycloakAdminClient.realm(realmName);
  }

  /** Returns the client's configured clientId (not its internal UUID). */
  public String createClient(RealmResource realmResource, ClientRepresentation client) {
    try (Response response = realmResource.clients().create(client)) {
      if (response.getStatus() >= 300) {
        throw new IllegalStateException("Failed to create client, status=" + response.getStatus());
      }
    }
    return client.getClientId();
  }

  /**
   * Enables storing arbitrary custom user attributes (tenantId, userId)
   * without declaring each one in the realm's User Profile schema first.
   */
  public void relaxUserProfile(RealmResource realmResource) {
    UPConfig upConfig = realmResource.users().userProfile().getConfiguration();
    upConfig.setUnmanagedAttributePolicy(UnmanagedAttributePolicy.ENABLED);
    realmResource.users().userProfile().update(upConfig);
  }
}
