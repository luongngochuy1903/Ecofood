package com.ecofood.app.notification;

import com.ecofood.app.notification.NotificationContracts.*;
import com.ecofood.core.dto.PageResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/customers/me")
public class CustomerNotificationController {
  private final NotificationService service;
  private final NotificationPreferenceService preferences;
  @GetMapping("/notifications")
  public ResponseEntity<PageResponse<NotificationView>> list(@RequestParam(required = false) Type type, @RequestParam(required = false) Boolean isRead,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) { return response(service.list(type, isRead, page, size)); }
  @GetMapping("/notifications/unread-count")
  public ResponseEntity<UnreadCount> unread(@RequestParam(required = false) Type type) { return response(service.unreadCount(type)); }
  @PatchMapping("/notifications/{id}/read")
  public ResponseEntity<NotificationView> read(@PathVariable String id, @Valid @RequestBody ReadRequest request) { return response(service.markRead(id, request.isRead())); }
  @PatchMapping("/notifications/read-all")
  public ResponseEntity<ReadAllResult> readAll(@RequestBody(required = false) ReadAllRequest ignored) { return response(service.readAll()); }
  @GetMapping("/notification-preferences") public ResponseEntity<PreferenceView> preferences() { return response(preferences.getMine()); }
  @PatchMapping("/notification-preferences") public ResponseEntity<PreferenceView> preferences(@Valid @RequestBody PreferenceRequest request) { return response(preferences.updateMine(request)); }
  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("message", "Invalid notification request")); }
  private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(body); }
}
