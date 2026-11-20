package com.ecofood.app.orchestrator;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = PlanSaleOrchestratorController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlanSaleErrorHandler {
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> invalidPayload(HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "message",
                "Malformed Sale configuration, unsupported field type or validation rule"));
  }
}
