package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserKeycloakServiceTest {

  @Mock private RealmResource realmResource;
  @Mock private UsersResource usersResource;
  @Mock private UserResource userResource;
  @Mock private Response response;

  private final UserKeycloakService userKeycloakService = new UserKeycloakService();

  @Test
  void createUserReturnsTheKeycloakAssignedIdOnSuccess() {
    when(realmResource.users()).thenReturn(usersResource);
    UserRepresentation user = new UserRepresentation();
    when(usersResource.create(user)).thenReturn(response);
    when(response.getStatus()).thenReturn(201);
    when(response.getStatusInfo()).thenReturn(Response.Status.CREATED);
    when(response.getLocation())
        .thenReturn(URI.create("http://localhost:8080/admin/realms/acme/users/a1b2c3d4-keycloak-id"));

    String createdId = userKeycloakService.createUser(realmResource, user);

    assertThat(createdId).isEqualTo("a1b2c3d4-keycloak-id");
  }

  @Test
  void createUserThrowsAndClosesTheResponseWhenKeycloakRejectsTheCreate() {
    when(realmResource.users()).thenReturn(usersResource);
    UserRepresentation user = new UserRepresentation();
    when(usersResource.create(user)).thenReturn(response);
    when(response.getStatus()).thenReturn(409);

    assertThatThrownBy(() -> userKeycloakService.createUser(realmResource, user))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("409");

    verify(response).close();
  }

  @Test
  void sendVerifyEmailDelegatesToTheUsersResourceForThatUserId() {
    when(realmResource.users()).thenReturn(usersResource);
    when(usersResource.get("a1b2c3d4-keycloak-id")).thenReturn(userResource);

    userKeycloakService.sendVerifyEmail(realmResource, "a1b2c3d4-keycloak-id");

    verify(userResource).sendVerifyEmail();
  }
}
