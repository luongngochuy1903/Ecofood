package com.ecofood.app.feedback.controller;

import com.ecofood.app.feedback.dto.*;
import com.ecofood.app.feedback.service.ServiceFeedbackService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/receipts/{token}")
public class ServiceFeedbackController {
  private final ServiceFeedbackService service;

  @PostMapping("/feedback")
  public ResponseEntity<ServiceFeedbackStatusResponse> submit(@PathVariable String token,
      @Valid @RequestBody SubmitServiceFeedbackRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer").body(service.submit(token, request));
  }

  @GetMapping("/feedback-status")
  public ResponseEntity<ServiceFeedbackStatusResponse> status(@PathVariable String token) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer").body(service.status(token));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> invalidRequest() {
    return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
        .body(Map.of("message", "Invalid service feedback request"));
  }
}
