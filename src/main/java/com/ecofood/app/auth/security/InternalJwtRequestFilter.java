package com.ecofood.app.auth.security;

import com.ecofood.app.auth.service.InternalTokenService;
import com.ecofood.app.common.security.AuthenticationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates this app's own internal access token on every request. The
 * "iss"/"aud" checks read plain custom map claims (claims.get("iss"),
 * claims.get("aud")) — NOT jjwt's registered Claims.getIssuer()/getAudience()
 * accessors — because InternalTokenService (matching DCMP's real
 * JwtTokenUtil) never calls .issuer(...)/.audience(...) on the token
 * builder; those values only ever exist as ordinary map entries.
 *
 * Explicit 401 + structured JSON body ({"message":"Unauthorized"}) instead
 * of letting Spring Security fall through to a bare 403, with a specific
 * logged reason per rejection, and a blocklist check (via
 * InternalTokenService.verifyToken()) so a revoked token is rejected
 * immediately on logout, not just once it would have expired anyway.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalJwtRequestFilter extends OncePerRequestFilter {

  private static final String CLAIM_ISS = "iss";
  private static final String CLAIM_AUD = "aud";
  private static final String CLAIM_PERMISSIONS = "permissions";

  @Value("${jwt.issuer}")
  private String expectedIssuer;

  @Value("${jwt.audience}")
  private String expectedAudience;

  @Value("${jwt.secret}")
  private String jwtSecret;

  private final InternalTokenService internalTokenService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    final String requestTokenHeader = request.getHeader("Authorization");

    try {
      if (requestTokenHeader != null && requestTokenHeader.startsWith("Bearer ")) {
        String token = requestTokenHeader.substring(7);
        AuthenticationContext.setToken(requestTokenHeader);
        AuthenticationContext.setSecret(jwtSecret);

        try {
          Claims claims = internalTokenService.verifyToken(token);

          String jti = claims.getId();
          if (jti == null || jti.isEmpty()) {
            log.warn("Rejecting request with 401: missing jti claim in internal token");
            setUnauthorizedStatusToRespond(response);
            return;
          }

          Object issClaim = claims.get(CLAIM_ISS);
          String iss = issClaim != null ? issClaim.toString() : null;
          if (iss != null && !iss.isBlank() && !Objects.equals(iss, expectedIssuer)) {
            log.warn("Rejecting request with 401: issuer mismatch, jti={}, issuer={}", jti, iss);
            setUnauthorizedStatusToRespond(response);
            return;
          }

          // jjwt treats "aud" as a registered multi-valued claim per the JWT
          // spec (RFC 7519 allows a string OR an array) and coerces it into a
          // Collection even when set as a plain map entry via
          // InternalTokenService's generic .claims(map) builder (confirmed:
          // it is never actually a bare String at runtime) — audClaim.toString()
          // alone would yield "[app-api]", which can never equal
          // expectedAudience and would reject every request with a non-blank
          // audience claim.
          Object audClaim = claims.get(CLAIM_AUD);
          String aud =
              audClaim instanceof java.util.Collection<?> audCollection
                  ? audCollection.stream().findFirst().map(Object::toString).orElse(null)
                  : audClaim != null ? audClaim.toString() : null;
          if (aud != null && !aud.isBlank() && !Objects.equals(aud, expectedAudience)) {
            log.warn("Rejecting request with 401: audience mismatch, jti={}, audience={}", jti, aud);
            setUnauthorizedStatusToRespond(response);
            return;
          }

          if (SecurityContextHolder.getContext().getAuthentication() == null) {
            @SuppressWarnings("unchecked")
            List<String> permissions = claims.get(CLAIM_PERMISSIONS, List.class);
            List<GrantedAuthority> authorities =
                permissions == null
                    ? List.of()
                    : permissions.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());

            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
          }
        } catch (Exception e) {
          log.warn("An error occurred while parsing/verifying internal token", e);
          setUnauthorizedStatusToRespond(response);
          return;
        }
      }

      chain.doFilter(request, response);
    } finally {
      // ThreadLocals — must always be cleared, or they leak across pooled
      // threads onto an unrelated later request.
      AuthenticationContext.unload();
    }
  }

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static void setUnauthorizedStatusToRespond(HttpServletResponse response) throws IOException {
    response.setContentType("application/json;charset=UTF-8");
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.getWriter().write(OBJECT_MAPPER.writeValueAsString(Map.of("message", "Unauthorized")));
  }
}
