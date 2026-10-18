package com.ecofood.app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /api/v1/auth/refresh-token — proactive silent refresh, called on a timer by the frontend. */
@Data
public class RefreshTokenRequest {

  @NotBlank
  private String refreshToken;
}
