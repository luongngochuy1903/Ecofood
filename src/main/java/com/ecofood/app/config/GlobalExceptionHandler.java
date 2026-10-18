package com.ecofood.app.config;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps service-layer exceptions to proper HTTP status codes, in the same
 * {"message": "..."} shape InternalJwtAuthenticationEntryPoint/
 * InternalJwtRequestFilter already use for 401s.
 *
 * Without this, an uncaught exception falls through to Spring Boot's
 * default /error forward — which Spring Security's filter chain also
 * re-evaluates as a separate request, masking the real status with its own
 * 401/403 if "/error" isn't explicitly permitted (see SecurityConfig).
 * Handling exceptions here, inside the normal DispatcherServlet flow,
 * avoids that forward entirely — the response is written before Spring
 * Security's error-dispatch handling ever comes into play.
 *
 * Status mapping follows this codebase's existing convention (see all the
 * orElseThrow(...) call sites across the service layer): IllegalArgumentException
 * is used for "X not found"/invalid-input lookups, IllegalStateException for
 * a precondition/external-dependency failure (e.g. a Keycloak admin call
 * failing, or a role that must be created manually but isn't).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
    // Every built-in Spring MVC exception (unmatched route, wrong HTTP
    // method, bad request param, validation failure, ...) implements
    // ErrorResponse and already carries its own correct status — deferring
    // to it here avoids this catch-all forcing those down to a wrong 500.
    if (ex instanceof ErrorResponse errorResponse) {
      String detail = errorResponse.getBody().getDetail();
      return ResponseEntity.status(errorResponse.getStatusCode())
          .body(Map.of("message", detail != null ? detail : ex.getMessage()));
    }
    log.error("Unhandled exception while processing request", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("message", "An unexpected error occurred"));
  }
}
