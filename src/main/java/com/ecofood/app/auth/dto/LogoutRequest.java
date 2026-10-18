package com.ecofood.app.auth.dto;

import lombok.Data;

/**
 * POST /api/v1/auth/logout — revokes whichever of the two tokens are sent.
 * Always 200: a missing/already-invalid token is a no-op, not an error, so
 * the frontend can call this unconditionally during logout.
 */
@Data
public class LogoutRequest {
  private String accessToken;
  private String refreshToken;
}
