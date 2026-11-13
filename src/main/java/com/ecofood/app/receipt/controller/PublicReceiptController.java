package com.ecofood.app.receipt.controller;

import com.ecofood.app.receipt.dto.PublicReceiptResponse;
import com.ecofood.app.receipt.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/receipts")
public class PublicReceiptController {
  private final ReceiptService service;

  @GetMapping
  public ResponseEntity<PublicReceiptResponse> read(@RequestParam(defaultValue = "") String token) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer").body(service.read(token));
  }
}
