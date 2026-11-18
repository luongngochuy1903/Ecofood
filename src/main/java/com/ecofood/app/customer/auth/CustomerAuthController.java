package com.ecofood.app.customer.auth;

import com.ecofood.app.auth.dto.ExchangeTokenRequest;
import com.ecofood.app.auth.dto.ExchangeTokenResponse;
import com.ecofood.app.auth.dto.RefreshTokenRequest;
import com.ecofood.app.auth.service.InternalTokenService;
import com.ecofood.app.auth.service.KeycloakTokenVerifier;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.security.AuthenticationContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/customer-auth")
public class CustomerAuthController {
  private final CustomerTenantResolver portalTenants;
  private final KeycloakTokenVerifier verifier;
  private final CustomerIdentityService identities;
  private final InternalTokenService tokens;

  public record LoginConfig(String issuer, String clientId, String tenantId) {}
  public record CustomerAccount(String id, String email, String firstName, String lastName, String userCategory, String tenantId) {}

  @GetMapping("/config")
  public ResponseEntity<LoginConfig> config() {
    var portal = portalTenants.resolve();
    return noStore(new LoginConfig(portal.issuer(), portal.clientId(), portal.tenantId()));
  }

  @PostMapping("/exchange")
  public ResponseEntity<ExchangeTokenResponse> exchange(@Valid @RequestBody ExchangeTokenRequest request) {
    var portal = portalTenants.resolve();
    com.nimbusds.jwt.JWTClaimsSet claims;
    try { claims = verifier.verifyForIssuer(request.getAccessToken(), portal.issuer()); }
    catch (IllegalArgumentException ex) { throw unauthorized(); }
    UserEntity user = identities.provision(claims);
    return tokenResponse(user, portal);
  }

  @PostMapping("/refresh-token")
  public ResponseEntity<ExchangeTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    var portal = portalTenants.resolve();
    io.jsonwebtoken.Claims claims;
    try { claims = tokens.verifyRefreshToken(request.getRefreshToken()); }
    catch (IllegalArgumentException ex) { throw unauthorized(); }
    if (!"CUSTOMER".equals(claims.get("identityType")) || !portal.tenantId().equals(claims.get("tenantId"))
        || !portal.clientId().equals(claims.get("clientId"))) throw unauthorized();
    return tokenResponse(identities.requireAccount(claims.getSubject()), portal);
  }

  @GetMapping("/me")
  public ResponseEntity<CustomerAccount> me() {
    var claims = AuthenticationContext.getClaims();
    var portal = portalTenants.resolve();
    if (!"CUSTOMER".equals(claims.get("identityType")) || !portal.tenantId().equals(claims.get("tenantId"))
        || !portal.clientId().equals(claims.get("clientId"))) throw unauthorized();
    var user = identities.requireAccount(claims.getSubject());
    return noStore(new CustomerAccount(user.getIdAsString(), user.getEmail(), user.getFirstName(), user.getLastName(), "CUSTOMER", portal.tenantId()));
  }

  private ResponseEntity<ExchangeTokenResponse> tokenResponse(UserEntity user, CustomerTenantResolver.Portal portal) {
    var pair = tokens.mintCustomerTokenPair(user.getIdAsString(), user.getEmail(), portal);
    return noStore(new ExchangeTokenResponse(pair.accessToken(), pair.refreshToken(), null));
  }
  private <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(body);
  }
  private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"); }
}
