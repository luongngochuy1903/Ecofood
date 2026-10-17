package com.ecofood.app.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Hitting a protected endpoint with no valid credential at all returns 401 +
 * a structured {"message":"Unauthorized"} JSON body, instead of Spring
 * Security's bare default 403 — same shape every DCMP service uses.
 * Registered on the SecurityFilterChain's exceptionHandling.
 */
@Component
public class InternalJwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
      throws IOException {
    response.setContentType("application/json;charset=UTF-8");
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.getWriter().write(OBJECT_MAPPER.writeValueAsString(Map.of("message", "Unauthorized")));
  }
}
