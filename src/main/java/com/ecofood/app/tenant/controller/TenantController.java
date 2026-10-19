package com.ecofood.app.tenant.controller;

import com.ecofood.core.dto.TenantLoginInfoResponse;
import com.ecofood.core.dto.TenantOnboardRequest;
import com.ecofood.core.dto.TenantOnboardResponse;
import com.ecofood.core.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant onboarding — creates the tenant's Keycloak realm + client AND its first user in one call. */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

  private final TenantService tenantService;

  @PostMapping
  public ResponseEntity<TenantOnboardResponse> onboardTenant(@Valid @RequestBody TenantOnboardRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(tenantService.onboardTenant(request));
  }

  /**
   * Public, unauthenticated lookup the frontend calls before any login
   * attempt — resolves the hostname it's running under (or a configured
   * stand-in for local development) to the realm/clientId it should redirect
   * to for Keycloak login.
   */
  @GetMapping("/login-info")
  public ResponseEntity<TenantLoginInfoResponse> getLoginInfo(@RequestParam String domain) {
    return ResponseEntity.ok(tenantService.getLoginInfoByDomain(domain));
  }
}
