package com.ecofood.app.transaction.controller;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.transaction.dto.BatchTransactionDtos;
import com.ecofood.app.transaction.service.BatchTransactionService;
import com.ecofood.core.security.AuthenticationContext;
import jakarta.validation.Valid;
import com.ecofood.app.metrics.service.KpiProcessingException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sales/transactions")
public class BatchTransactionController {
  private final BatchTransactionService service;
  @PostMapping
  public BatchTransactionDtos.Response submit(@Valid @RequestBody BatchTransactionDtos.Request request) {
    var permissions=AuthenticationContext.getPermissions();
    var claims=AuthenticationContext.getClaims();
    if(permissions==null || !permissions.contains("TRANSACTION_SUBMIT") || claims==null) throw new ForbiddenException("TRANSACTION_SUBMIT required");
    return service.submit(claims.get("tenantId",String.class),claims.getSubject(),request);
  }
  @ExceptionHandler(KpiProcessingException.class)
  public ResponseEntity<Map<String,String>> unavailable(KpiProcessingException failure) {
    return ResponseEntity.status(503).body(Map.of("code",failure.code(),
        "message","Batch not accepted: MongoDB transaction capability is unavailable"));
  }
}
