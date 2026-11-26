package com.ecofood.app.customer.controller;

import com.ecofood.app.customer.config.CustomerSettings;
import com.ecofood.core.dto.CustomerIdentityDtos;
import com.ecofood.core.service.CustomerIdentityService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/customers")
@RequiredArgsConstructor
public class CustomerAuthController {
  private final CustomerIdentityService identity;
  private final CustomerSettings settings;
  @GetMapping("/config") public ResponseEntity<CustomerIdentityDtos.LoginConfig> config(@RequestParam String tenantId) { return CustomerController.noStore(identity.config(tenantId)); }
  @PostMapping("/bootstrap") public ResponseEntity<Map<String, String>> bootstrap(@RequestBody CustomerIdentityDtos.Bootstrap input) { return CustomerController.noStore(Map.of("userId", identity.bootstrap(input))); }
  /** Lets the frontend resolve a Tenant to log a Customer into when none is given in the URL (e.g. the external-customer QR's loginUrl, which no longer carries ?tenant=). */
  @GetMapping("/default-tenant") public ResponseEntity<Map<String, String>> defaultTenant() { return CustomerController.noStore(Map.of("tenantId", settings.onboardingTenantId())); }
}
