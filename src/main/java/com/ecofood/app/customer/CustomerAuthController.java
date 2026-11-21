package com.ecofood.app.customer;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/customers")
@RequiredArgsConstructor
public class CustomerAuthController {
  private final CustomerIdentity identity;
  @GetMapping("/config") public ResponseEntity<CustomerIdentity.LoginConfig> config(@RequestParam String tenantId) { return CustomerController.noStore(identity.config(tenantId)); }
  @PostMapping("/bootstrap") public ResponseEntity<Map<String, String>> bootstrap(@RequestBody CustomerIdentity.Bootstrap input) { return CustomerController.noStore(Map.of("userId", identity.bootstrap(input))); }
}
