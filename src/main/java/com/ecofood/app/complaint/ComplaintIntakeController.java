package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintIntakeRequests.*;
import com.ecofood.app.complaint.ComplaintIntakeResponses.*;
import com.ecofood.core.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class ComplaintIntakeController {
  private final ComplaintIntakeService service;
  @PostMapping("/api/v1/public/invoice-complaints")
  public ResponseEntity<GuestConfirmation> guest(@Valid @RequestBody GuestInvoice request) { return created(service.guest(request.receiptToken(), request.issue())); }
  /** Compatibility alias; new clients use the body-token endpoint to avoid credentials in request URLs. */
  @PostMapping("/api/v1/public/receipts/{token}/complaints")
  public ResponseEntity<GuestConfirmation> guestAlias(@PathVariable String token, @Valid @RequestBody InvoiceIssue request) { return created(service.guest(token, request)); }
  @PostMapping("/api/v1/public/invoice-complaints/options")
  public ResponseEntity<Options> guestOptions(@Valid @RequestBody ReceiptProof request) { return ok(service.guestOptions(request.receiptToken())); }
  @PostMapping("/api/v1/public/complaints/track")
  public ResponseEntity<Detail> track(@Valid @RequestBody Track request, @RequestParam(defaultValue="0") int historyPage, @RequestParam(defaultValue="100") int historySize) { return ok(historyPage == 0 && historySize == 100 ? service.track(request) : service.track(request, historyPage, historySize)); }
  @PostMapping("/api/v1/customers/me/complaints/invoice")
  public ResponseEntity<Confirmation> invoice(@Valid @RequestBody CustomerInvoice request) { return created(service.customerInvoice(request)); }
  @PostMapping("/api/v1/customers/me/complaints/platform")
  public ResponseEntity<Confirmation> platform(@Valid @RequestBody PlatformIssue request) { return created(service.platform(request)); }
  @GetMapping("/api/v1/customers/me/complaint-options")
  public ResponseEntity<Options> options(@RequestParam Kind kind, @RequestParam(required=false) String invoiceId) { return ok(service.customerOptions(kind, invoiceId)); }
  @GetMapping("/api/v1/customers/me/complaints")
  public ResponseEntity<PageResponse<Summary>> mine(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size,
      @RequestParam(required=false) Kind kind, @RequestParam(required=false) Status status, @RequestParam(required=false) String q,
      @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.Instant from,
      @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.Instant to) { return ok(service.customerMine(page, size, kind, status, q, from, to)); }
  @GetMapping("/api/v1/customers/me/complaints/{id}")
  public ResponseEntity<Detail> detail(@PathVariable String id, @RequestParam(defaultValue="0") int historyPage, @RequestParam(defaultValue="100") int historySize) { return ok(historyPage == 0 && historySize == 100 ? service.customerDetail(id) : service.customerDetail(id, historyPage, historySize)); }
  @PostMapping("/api/v1/complaints/internal")
  public ResponseEntity<Confirmation> internal(@Valid @RequestBody InternalIssue request) { return created(service.internal(request)); }
  @GetMapping("/api/v1/complaints/internal/options")
  public ResponseEntity<Options> internalOptions() { return ok(service.internalOptions()); }
  @GetMapping("/api/v1/complaints/internal/mine")
  public ResponseEntity<PageResponse<Summary>> internalMine(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size,
      @RequestParam(required=false) Status status) { return ok(service.employeeMine(page, size, status)); }
  @GetMapping("/api/v1/complaints/internal/mine/{id}")
  public ResponseEntity<Detail> internalDetail(@PathVariable String id) { return ok(service.employeeDetail(id)); }
  private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(value); }
  private <T> ResponseEntity<T> created(T value) { return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(value); }
}
