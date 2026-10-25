package com.ecofood.app.config;

/**
 * Service-layer "caller is authenticated but not allowed to do this" signal
 * — deliberately NOT org.springframework.security.access.AccessDeniedException,
 * since Spring Security's ExceptionTranslationFilter intercepts that type
 * before it ever reaches GlobalExceptionHandler, bypassing this project's
 * uniform {"message": "..."} error-body convention.
 */
public class ForbiddenException extends RuntimeException {
  public ForbiddenException(String message) {
    super(message);
  }
}
