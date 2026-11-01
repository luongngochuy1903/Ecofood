package com.ecofood.app.config;

/**
 * Service-layer "the request itself is malformed" signal (e.g. an
 * unsupported sort field) — distinct from IllegalArgumentException, which
 * this codebase's convention reserves for "X not found" lookups (see
 * GlobalExceptionHandler).
 */
public class BadRequestException extends RuntimeException {
  public BadRequestException(String message) {
    super(message);
  }
}
