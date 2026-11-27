package com.ecofood.app.transaction.controller;

import com.ecofood.app.config.Audited;
import com.ecofood.app.config.AuditInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import com.ecofood.core.enums.AuditAction;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.transaction.csv.BatchTransactionCsvParser;
import com.ecofood.app.transaction.dto.BatchTransactionDtos;
import com.ecofood.app.transaction.service.BatchTransactionService;
import com.ecofood.core.security.AuthenticationContext;
import jakarta.validation.Valid;
import com.ecofood.app.metrics.service.KpiProcessingException;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sales/transactions")
public class BatchTransactionController {
  private final BatchTransactionService service;
  private final BatchTransactionCsvParser csvParser;

  @Audited(AuditAction.CREATE)
  @PostMapping
  public BatchTransactionDtos.Response submit(@Valid @RequestBody BatchTransactionDtos.Request request,
      HttpServletRequest httpRequest) {
    var permissions=AuthenticationContext.getPermissions();
    var claims=AuthenticationContext.getClaims();
    if(permissions==null || !permissions.contains("TRANSACTION_SUBMIT") || claims==null) throw new ForbiddenException("TRANSACTION_SUBMIT required");
    var result = service.submit(claims.get("tenantId",String.class),claims.getSubject(),request);
    if (result.transaction().invoices().stream().noneMatch(invoice -> "ACCEPTED".equals(invoice.result()))) {
      httpRequest.setAttribute(AuditInterceptor.BUSINESS_FAILURE, true);
    }
    return result;
  }

  /**
   * Same submit path as {@link #submit}, just sourced from a CSV upload
   * instead of a JSON body — BatchTransactionCsvParser builds the identical
   * Request shape, then this delegates to the exact same
   * BatchTransactionService.submit(), so every validation/persistence/
   * confirmation/metrics rule applies unchanged.
   */
  @Audited(AuditAction.CREATE)
  @PostMapping(path = "/csv", consumes = "multipart/form-data")
  public BatchTransactionDtos.Response submitCsv(@RequestParam("file") MultipartFile file,
      HttpServletRequest httpRequest) {
    var permissions=AuthenticationContext.getPermissions();
    var claims=AuthenticationContext.getClaims();
    if(permissions==null || !permissions.contains("TRANSACTION_SUBMIT") || claims==null) throw new ForbiddenException("TRANSACTION_SUBMIT required");
    if (file == null || file.isEmpty()) throw new BadRequestException("CSV file is required");
    BatchTransactionDtos.Request request;
    try {
      request = csvParser.parse(file.getInputStream());
    } catch (IOException failure) {
      throw new BadRequestException("Unable to read CSV file: " + failure.getMessage());
    }
    var result = service.submit(claims.get("tenantId",String.class),claims.getSubject(),request);
    if (result.transaction().invoices().stream().noneMatch(invoice -> "ACCEPTED".equals(invoice.result()))) {
      httpRequest.setAttribute(AuditInterceptor.BUSINESS_FAILURE, true);
    }
    return result;
  }
  @ExceptionHandler(KpiProcessingException.class)
  public ResponseEntity<Map<String,String>> unavailable(KpiProcessingException failure) {
    return ResponseEntity.status(503).body(Map.of("code",failure.code(),
        "message","Batch not accepted: MongoDB transaction capability is unavailable"));
  }
}
