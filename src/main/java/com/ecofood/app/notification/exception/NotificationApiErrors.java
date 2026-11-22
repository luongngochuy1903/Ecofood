package com.ecofood.app.notification.exception;

import com.ecofood.app.notification.controller.NotificationController;
import com.ecofood.app.notification.entity.Notification;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Scope malformed-input handling to UC-016 without changing other APIs. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationApiErrors {
  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String, String>> malformed(Exception ignored) {
    return ResponseEntity.badRequest().body(Map.of("message", "Payload hoặc tham số Notification không hợp lệ"));
  }
}
