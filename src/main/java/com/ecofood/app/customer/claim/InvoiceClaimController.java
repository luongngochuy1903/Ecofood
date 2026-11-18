package com.ecofood.app.customer.claim;

import com.ecofood.app.customer.claim.InvoiceClaimContracts.*;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/customer/invoice-claims")
public class InvoiceClaimController {
  private final InvoiceClaimService service;
  @PostMapping("/eligibility")
  public ResponseEntity<Eligibility> eligibility(@Valid @RequestBody ReceiptRequest request) { return response(service.eligibility(request)); }
  @PostMapping("/status")
  public ResponseEntity<ClaimStatusResponse> status(@Valid @RequestBody ReceiptRequest request) { return response(service.status(request)); }
  @PostMapping
  public ResponseEntity<ClaimResult> claim(@Valid @RequestBody ClaimRequest request) { return response(service.claim(request)); }
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> invalidRequest() {
    return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("message", "Invalid claim request"));
  }
  private <T> ResponseEntity<T> response(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(body);
  }
}
