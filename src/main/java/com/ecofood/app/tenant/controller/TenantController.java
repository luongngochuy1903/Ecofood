package com.ecofood.app.tenant.controller;

import com.ecofood.core.dto.DomainAvailabilityResponse;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.TenantLoginInfoResponse;
import com.ecofood.core.dto.TenantOnboardRequest;
import com.ecofood.core.dto.TenantOnboardResponse;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.dto.TenantUpdateRequest;
import com.ecofood.core.enums.TenantTypeEnum;
import com.ecofood.core.service.TenantService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
   * Lists tenants, optionally filtered by tenantType — e.g.
   * ?tenantType=MASTER_TENANT backs the onboarding form's master-tenant
   * picker (shown when the new tenant is being onboarded as a SUB_TENANT).
   */
  @GetMapping
  public ResponseEntity<List<TenantResponse>> listTenants(
      @RequestParam(required = false) TenantTypeEnum tenantType) {
    return ResponseEntity.ok(tenantService.listTenants(tenantType));
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

  /**
   * Live "is this domain already taken" check for the onboarding form —
   * called with the full computed domain (e.g. "gamma.ecofood.com" or
   * "master.ecofood.com/sub"), never the raw subdomain box the user types.
   */
  @GetMapping("/domain-availability")
  public ResponseEntity<DomainAvailabilityResponse> checkDomainAvailability(
      @RequestParam String domainUrl) {
    return ResponseEntity.ok(tenantService.checkDomainAvailability(domainUrl));
  }

  /**
   * The platform's one ECO tenant — the onboarding form uses its domainUrl
   * as the base every other tenant's domain is composed against (e.g.
   * "gamma.<rootTenant.domainUrl>"). 404s if the platform hasn't been
   * bootstrapped yet (see docs for the ECO-onboarding bootstrap sequence).
   */
  @GetMapping("/root")
  public ResponseEntity<TenantResponse> getRootTenant() {
    return ResponseEntity.ok(tenantService.getRootTenant());
  }

  /**
   * Backs the "Tenant" sub-tab — paginated, searchable, role-scoped listing
   * (see TenantService.listTenantDirectory). Authenticated-only: SecurityConfig
   * only permits the exact onboarding/pre-login tenant endpoints above,
   * everything else (this one included) falls through to the default
   * anyRequest().authenticated().
   */
  @GetMapping("/directory")
  public ResponseEntity<PageResponse<TenantResponse>> getTenantDirectory(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "updatedAt,desc") String sort,
      @RequestParam(required = false) String search) {
    return ResponseEntity.ok(tenantService.listTenantDirectory(page, size, sort, search));
  }

  /**
   * One tenant's full detail — backs the directory table's row-click popup
   * (see CoreTable's onRowClick in EcoFood-FEcore, mirroring DCMP's own
   * click-a-row-to-view-detail pattern). Authenticated-only, same as
   * /directory; not scoped by role beyond that — any authenticated caller
   * can resolve any tenant id (no ownership check yet, see TenantService
   * javadoc).
   */
  @GetMapping("/{id}")
  public ResponseEntity<TenantResponse> getById(@PathVariable String id) {
    return ResponseEntity.ok(tenantService.getById(id));
  }

  /**
   * Updates exactly tenantName + contact — see TenantUpdateRequest/
   * TenantService.updateTenant javadoc for why it's this narrow and how it's
   * role-scoped. Authenticated-only, same as /directory and /{id}.
   */
  @PutMapping("/{id}")
  public ResponseEntity<TenantResponse> updateTenant(
      @PathVariable String id, @Valid @RequestBody TenantUpdateRequest request) {
    return ResponseEntity.ok(tenantService.updateTenant(id, request));
  }
}
