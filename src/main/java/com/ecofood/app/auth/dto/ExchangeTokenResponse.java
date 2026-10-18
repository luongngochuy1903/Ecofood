package com.ecofood.app.auth.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ExchangeTokenResponse {
  private String accessToken;
  private String refreshToken;
  /** The verified Keycloak token's own claims — null on a refresh (no Keycloak token involved). */
  private Map<String, Object> keycloakClaims;
}
