package com.ecofood.app.customer.controller;

import com.ecofood.app.customer.dto.CustomerInvoiceResponse;
import com.ecofood.app.customer.dto.CustomerProfileResponse;
import com.ecofood.app.customer.dto.CustomerProfileUpdateRequest;
import com.ecofood.app.customer.dto.CustomerStatisticsResponse;
import com.ecofood.app.customer.service.CustomerPortalService;
import com.ecofood.app.customer.service.CustomerPurchaseService;
import com.ecofood.app.customer.dto.CustomerPurchaseFilter;
import com.ecofood.core.enums.TransactionStatus;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** UC-021: no client-supplied customerId or tenantId in self-service routes. */
@RestController
@RequestMapping("/api/v1/customers/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CustomerPortalController {
  private final CustomerPortalService customerPortalService;
  private final CustomerPurchaseService purchases;

  @GetMapping
  public ResponseEntity<CustomerProfileResponse> getMyProfile() {
    return noStore(customerPortalService.getMyProfile());
  }

  @PatchMapping
  public ResponseEntity<CustomerProfileResponse> updateMyProfile(
      @Valid @RequestBody CustomerProfileUpdateRequest request) {
    return noStore(customerPortalService.updateMyProfile(request));
  }

  @GetMapping("/invoices")
  public ResponseEntity<PageResponse<CustomerInvoiceResponse>> getMyInvoices(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String invoiceCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) String distributionPartnerId,
      @RequestParam(required = false) TransactionStatus status,
      @RequestParam(defaultValue = "transactionTime") String sort,
      @RequestParam(defaultValue = "desc") String direction) {
    return noStore(purchases.list(new CustomerPurchaseFilter(invoiceCode, from, to, distributionPartnerId, status), page, size, sort, direction));
  }

  @GetMapping("/invoices/{invoiceId}")
  public ResponseEntity<CustomerInvoiceResponse> getMyInvoice(@PathVariable String invoiceId) {
    return noStore(purchases.detail(invoiceId));
  }

  @GetMapping("/statistics")
  public ResponseEntity<CustomerStatisticsResponse> getMyStatistics(
      @RequestParam(required = false) String invoiceCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) String distributionPartnerId,
      @RequestParam(required = false) TransactionStatus status,
      @RequestParam(defaultValue = "UTC") String timeZone) {
    return noStore(purchases.statistics(new CustomerPurchaseFilter(invoiceCode, from, to, distributionPartnerId, status), timeZone));
  }

  private <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(body);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, String>> invalidFilter() {
    return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("message", "Invalid purchase filter or pagination"));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> invalidProfileRequest() {
    return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("message", "Invalid profile request; only firstName, lastName, phoneNumber and address can be updated"));
  }
}
