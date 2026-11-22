package com.ecofood.app.customer.controller;

import com.ecofood.core.dto.CustomerReceiptDtos;
import com.ecofood.core.service.CustomerReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CustomerReceiptController {
  private final CustomerReceiptService service;
  @PostMapping("/api/v1/transactions/{invoiceId}/customer-receipt")
  public ResponseEntity<CustomerReceiptDtos.Issued> issue(@PathVariable String invoiceId, @RequestBody CustomerReceiptDtos.IssueRequest request) { return CustomerController.noStore(service.issue(invoiceId, request)); }
  @DeleteMapping("/api/v1/transactions/{invoiceId}/customer-receipt")
  public ResponseEntity<Void> revoke(@PathVariable String invoiceId) { service.revoke(invoiceId); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
  // Receipt bearer authentication, no User/JWT required. POST keeps the secret out of query/access logs.
  @PostMapping("/api/v1/auth/customer-receipts/view")
  public ResponseEntity<CustomerReceiptDtos.GuestInvoice> view(@RequestBody CustomerReceiptDtos.TokenRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(service.view(request == null ? null : request.token()));
  }
  @PostMapping("/api/v1/customers/me/invoices/claim")
  public ResponseEntity<CustomerReceiptDtos.Claimed> claim(@RequestBody CustomerReceiptDtos.TokenRequest request) { return CustomerController.noStore(service.claim(request == null ? null : request.token())); }
}
