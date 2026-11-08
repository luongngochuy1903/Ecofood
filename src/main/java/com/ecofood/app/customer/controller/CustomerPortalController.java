package com.ecofood.app.customer.controller;

import com.ecofood.app.customer.dto.CustomerInvoiceResponse;
import com.ecofood.app.customer.dto.CustomerProfileResponse;
import com.ecofood.app.customer.dto.CustomerProfileUpdateRequest;
import com.ecofood.app.customer.dto.CustomerStatisticsResponse;
import com.ecofood.app.customer.service.CustomerPortalService;
import com.ecofood.core.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** UC-021: no client-supplied customerId or tenantId in self-service routes. */
@RestController
@RequestMapping("/api/v1/customers/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CustomerPortalController {
  private final CustomerPortalService customerPortalService;

  @GetMapping
  public ResponseEntity<CustomerProfileResponse> getMyProfile() {
    return ResponseEntity.ok(customerPortalService.getMyProfile());
  }

  @PatchMapping
  public ResponseEntity<CustomerProfileResponse> updateMyProfile(
      @Valid @RequestBody CustomerProfileUpdateRequest request) {
    return ResponseEntity.ok(customerPortalService.updateMyProfile(request));
  }

  @GetMapping("/invoices")
  public ResponseEntity<PageResponse<CustomerInvoiceResponse>> getMyInvoices(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    return ResponseEntity.ok(customerPortalService.getMyInvoices(page, size));
  }

  @GetMapping("/invoices/{invoiceId}")
  public ResponseEntity<CustomerInvoiceResponse> getMyInvoice(@PathVariable String invoiceId) {
    return ResponseEntity.ok(customerPortalService.getMyInvoice(invoiceId));
  }

  @GetMapping("/statistics")
  public ResponseEntity<CustomerStatisticsResponse> getMyStatistics() {
    return ResponseEntity.ok(customerPortalService.getMyStatistics());
  }
}
