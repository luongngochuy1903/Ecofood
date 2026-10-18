package com.ecofood.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /api/v1/auth/exchange — the Keycloak access token the frontend already obtained. */
@Data
public class ExchangeTokenRequest {

  @NotBlank
  private String accessToken;
}
