package com.ecofood.app.config;

import static com.fasterxml.jackson.annotation.JsonInclude.Include;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Admin-level Keycloak client used by tenant/user onboarding to create
 * realms/clients/users via the Keycloak Admin REST API. Authenticates
 * against the master realm with the bootstrap admin account.
 *
 * The underlying REST client registers a custom Jackson provider — the
 * admin-client SDK's default ObjectMapper can fail to deserialize
 * java.time.* fields or choke on response fields its representation classes
 * don't declare, which happens whenever the Keycloak server is a newer
 * patch version than this SDK.
 */
@Configuration
public class KeycloakAdminConfig {

  @Value("${keycloak.admin.server-url}")
  private String serverUrl;

  @Value("${keycloak.admin.realm}")
  private String realm;

  @Value("${keycloak.admin.client-id}")
  private String clientId;

  @Value("${keycloak.admin.username}")
  private String username;

  @Value("${keycloak.admin.password}")
  private String password;

  @Bean
  public Keycloak keycloakAdminClient() {
    return KeycloakBuilder.builder()
        .serverUrl(serverUrl)
        .realm(realm)
        .clientId(clientId)
        .grantType("password")
        .username(username)
        .password(password)
        .resteasyClient(
            new ResteasyClientBuilderImpl().register(new CustomRestEasyJackson2Provider(), 1000).build())
        .build();
  }

  public static class CustomRestEasyJackson2Provider extends ResteasyJackson2Provider {

    public CustomRestEasyJackson2Provider() {
      ObjectMapper mapper = new ObjectMapper();
      mapper.registerModule(new JavaTimeModule());
      mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
      mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false);
      mapper.setSerializationInclusion(Include.NON_NULL);
      setMapper(mapper);
    }
  }
}
