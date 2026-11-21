package com.ecofood.app.customer.controller;

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
  @GetMapping("/config") public ResponseEntity<CustomerIdentityDtos.LoginConfig> config(@RequestParam String tenantId) { return CustomerController.noStore(identity.config(tenantId)); }
  @PostMapping("/bootstrap") public ResponseEntity<Map<String, String>> bootstrap(@RequestBody CustomerIdentityDtos.Bootstrap input) { return CustomerController.noStore(Map.of("userId", identity.bootstrap(input))); }
}
