package com.ecofood.app.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.app.auth.service.InternalTokenService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InternalJwtRequestFilterTest {

  @Mock private InternalTokenService internalTokenService;
  @Mock private FilterChain filterChain;
  @Mock private Claims claims;

  private InternalJwtRequestFilter filter;

  @BeforeEach
  void setUp() {
    filter = new InternalJwtRequestFilter(internalTokenService);
    ReflectionTestUtils.setField(filter, "expectedIssuer", "app");
    ReflectionTestUtils.setField(filter, "expectedAudience", "app-api");
    ReflectionTestUtils.setField(filter, "jwtSecret", "unit-test-secret");
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  private MockHttpServletRequest requestWithBearerToken(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    return request;
  }

  @Test
  void passesThroughWithoutTouchingInternalTokenServiceWhenNoAuthorizationHeaderPresent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    verify(internalTokenService, never()).verifyToken(anyString());
  }

  @Test
  void acceptsAValidTokenWithAudienceAsACollectionMatchingExpectedAudience() throws Exception {
    // Regression test for the real bug this suite caught: jjwt coerces the
    // "aud" claim into a Collection even though InternalTokenService only
    // ever sets it as a plain map entry — a naive audClaim.toString() would
    // yield "[app-api]", which can never equal "app-api" and would reject
    // every request. See InternalJwtRequestFilter's audience-matching fix.
    when(claims.getId()).thenReturn("jti-1");
    when(claims.get("iss")).thenReturn("app");
    when(claims.get("aud")).thenReturn(new LinkedHashSet<>(java.util.List.of("app-api")));
    when(claims.get("permissions", java.util.List.class)).thenReturn(java.util.List.of("tenants.read.any"));
    when(claims.getSubject()).thenReturn("user-1");
    when(internalTokenService.verifyToken("good-token")).thenReturn(claims);

    MockHttpServletRequest request = requestWithBearerToken("good-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("user-1");
  }

  @Test
  void rejectsWithoutCallingChainWhenAudienceCollectionDoesNotMatchExpected() throws Exception {
    when(claims.getId()).thenReturn("jti-1");
    when(claims.get("iss")).thenReturn("app");
    when(claims.get("aud")).thenReturn(new LinkedHashSet<>(java.util.List.of("some-other-audience")));
    when(internalTokenService.verifyToken("bad-audience-token")).thenReturn(claims);

    MockHttpServletRequest request = requestWithBearerToken("bad-audience-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, never()).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void rejectsWithoutCallingChainWhenIssuerDoesNotMatchExpected() throws Exception {
    when(claims.getId()).thenReturn("jti-1");
    when(claims.get("iss")).thenReturn("http://someone-else.example.com");
    when(internalTokenService.verifyToken("bad-issuer-token")).thenReturn(claims);

    MockHttpServletRequest request = requestWithBearerToken("bad-issuer-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, never()).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void rejectsWithoutCallingChainWhenJtiClaimIsMissing() throws Exception {
    when(claims.getId()).thenReturn(null);
    when(internalTokenService.verifyToken("no-jti-token")).thenReturn(claims);

    MockHttpServletRequest request = requestWithBearerToken("no-jti-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, never()).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void rejectsWithoutCallingChainWhenTokenVerificationThrows() throws Exception {
    when(internalTokenService.verifyToken("garbage")).thenThrow(new IllegalArgumentException("Invalid internal token"));

    MockHttpServletRequest request = requestWithBearerToken("garbage");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, never()).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void clearsAuthenticationContextEvenWhenRequestIsRejected() throws Exception {
    when(internalTokenService.verifyToken("garbage")).thenThrow(new IllegalArgumentException("bad"));

    MockHttpServletRequest request = requestWithBearerToken("garbage");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(com.ecofood.core.security.AuthenticationContext.getToken()).isNull();
  }
}
