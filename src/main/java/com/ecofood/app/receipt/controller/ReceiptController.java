package com.ecofood.app.receipt.controller;

import com.ecofood.app.receipt.dto.IssueReceiptRequest;
import com.ecofood.app.receipt.dto.IssueReceiptResponse;
import com.ecofood.app.receipt.service.ReceiptService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/transactions/{invoiceId}/receipts")
public class ReceiptController {
  private final ReceiptService service;

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> invalidRequest() {
    return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
        .body(Map.of("message", "Invalid receipt request"));
  }

  @PostMapping
  public ResponseEntity<IssueReceiptResponse> issue(@PathVariable String invoiceId,
      @Valid @RequestBody IssueReceiptRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.issue(invoiceId, request));
  }

  @DeleteMapping("/{receiptId}")
  public ResponseEntity<Void> revoke(@PathVariable String invoiceId, @PathVariable String receiptId) {
    service.revoke(invoiceId, receiptId);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
