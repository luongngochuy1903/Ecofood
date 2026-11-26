package com.ecofood.app.customer.controller;

import com.ecofood.app.config.Audited;
import com.ecofood.core.dto.ExternalCustomerDtos;
import com.ecofood.core.enums.AuditAction;
import com.ecofood.core.service.ExternalCustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pre-transaction customer identification — a distribution partner hands us
 * a customer's email; see ExternalCustomerService javadoc for the whole
 * flow. Distinct from CustomerReceiptController, which issues a receipt QR
 * for an already-paid Invoice.
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
}
