package com.ecofood.app.tenant.controller;

import com.ecofood.app.common.dto.TenantOnboardRequest;
import com.ecofood.app.common.dto.TenantOnboardResponse;
import com.ecofood.app.common.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
