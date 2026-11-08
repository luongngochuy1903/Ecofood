package com.ecofood.app.transaction.controller;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.transaction.service.TransactionService;
import com.ecofood.app.transaction.service.TransactionQueryService;
import com.ecofood.app.transaction.dto.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.security.AuthenticationContext;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/transactions")
public class TransactionController {
  private final TransactionService service;
  private final TransactionQueryService queryService;

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, String>> invalidQueryParameter(MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.badRequest().body(Map.of("message", "Invalid value for parameter: " + ex.getName()));
  }

  @GetMapping
  public PageResponse<TransactionListItem> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String invoiceId,
      @RequestParam(required = false) String invoiceNumber,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) TransactionStatus status) {
    return queryService.list(new TransactionFilter(invoiceId, invoiceNumber, from, to, status), page, size);
  }

  @GetMapping("/summary")
  public TransactionSummaryResponse summary(
      @RequestParam(required = false) String invoiceId,
      @RequestParam(required = false) String invoiceNumber,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) TransactionStatus status) {
    return queryService.summary(new TransactionFilter(invoiceId, invoiceNumber, from, to, status));
  }

  @GetMapping("/{id}")
  public TransactionDetailResponse detail(@PathVariable String id) {
    return queryService.detail(id);
  }

  @PostMapping
  public ResponseEntity<SubmitTransactionResponse> submit(@Valid @RequestBody SubmitTransactionRequest request) {
    var permissions = AuthenticationContext.getPermissions();
    if (permissions == null || !permissions.contains("TRANSACTION_SUBMIT"))
      throw new ForbiddenException("Missing TRANSACTION_SUBMIT permission");
    var claims = AuthenticationContext.getClaims();
    String tenantId = claims.get("tenantId", String.class);
    String subject = claims.getSubject();
    if (tenantId == null || tenantId.isBlank() || subject == null || subject.isBlank())
      throw new ForbiddenException("POS token must identify tenant and subject");
    return ResponseEntity.status(HttpStatus.CREATED).body(service.submit(tenantId, subject, request));
  }
}
