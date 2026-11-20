package com.ecofood.app.transaction.controller;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.service.TransactionService;
import com.ecofood.core.dto.SubmitTransactionResponse;
import com.ecofood.core.security.AuthenticationContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class TransactionController {
  private final TransactionService service;

  private record Actor(String tenant, String subject) {}

  private Actor actor(String permission) {
    var permissions = AuthenticationContext.getPermissions();
    if (permissions == null || !permissions.contains(permission))
      throw new ForbiddenException("Missing " + permission + " permission");
    var claims = AuthenticationContext.getClaims();
    String tenant = claims == null ? null : claims.get("tenantId", String.class),
        subject = claims == null ? null : claims.getSubject();
    if (tenant == null || tenant.isBlank() || subject == null || subject.isBlank())
      throw new ForbiddenException("Verified Tenant and subject required");
    return new Actor(tenant, subject);
  }

  @PostMapping("/sales/{orchestratorId}/transactions")
  public ResponseEntity<SubmitTransactionResponse> submit(
      @PathVariable String orchestratorId, @RequestBody Map<String, Object> payload) {
    var actor = actor("TRANSACTION_SUBMIT");
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.submit(actor.tenant(), actor.subject(), orchestratorId, payload));
  }

  @GetMapping("/transactions")
  public Page<Invoice> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return service.list(actor("TRANSACTION_READ").tenant(), page, size);
  }

  @GetMapping("/transactions/{id}")
  public Invoice detail(@PathVariable String id) {
    return service.detail(actor("TRANSACTION_READ").tenant(), id);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> invalidPayload(HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest().body(Map.of("message", "Malformed transaction JSON"));
  }

  @ExceptionHandler(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, String>> invalidParameter(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Invalid query parameter: " + ex.getName()));
  }
}
