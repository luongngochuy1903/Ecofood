package com.ecofood.app.customer.controller;

import com.ecofood.app.config.Audited;
import com.ecofood.core.dto.CustomerIdentityDtos;
import com.ecofood.core.dto.ExternalCustomerDtos;
import com.ecofood.core.enums.AuditAction;
import com.ecofood.core.service.ExternalCustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Pre-transaction customer identification — a distribution partner issues a
 * QR for an externalId it generated (no Invoice/transaction exists yet); see
 * ExternalCustomerService javadoc for the whole flow. Distinct from
 * CustomerReceiptController, which issues a receipt QR for an
 * already-paid Invoice.
 */
@RestController
@RequiredArgsConstructor
public class ExternalCustomerController {
  private final ExternalCustomerService service;
  @Audited(AuditAction.CREATE)
  @PostMapping("/api/v1/external/customer")
  public ResponseEntity<ExternalCustomerDtos.Issued> issue(@Valid @RequestBody ExternalCustomerDtos.IssueRequest request) {
    return CustomerController.noStore(service.issue(request));
  }
  @Audited(AuditAction.DELETE)
  @DeleteMapping("/api/v1/external/customer/{externalId}")
  public ResponseEntity<Void> revoke(@PathVariable String externalId) {
    service.revoke(externalId);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
  // Bearer authentication, no User/JWT required. POST keeps the token out of query/access logs.
  @PostMapping("/api/v1/auth/external-customers/view")
  public ResponseEntity<CustomerIdentityDtos.LoginConfig> view(@RequestBody ExternalCustomerDtos.TokenRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
        .body(service.view(request == null ? null : request.token()));
  }
  @Audited(AuditAction.UPDATE)
  @PostMapping("/api/v1/customers/me/external-ids/link")
  public ResponseEntity<ExternalCustomerDtos.Linked> link(@RequestBody ExternalCustomerDtos.TokenRequest request) {
    return CustomerController.noStore(service.link(request == null ? null : request.token()));
  }
}
