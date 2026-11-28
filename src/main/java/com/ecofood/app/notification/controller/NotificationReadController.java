package com.ecofood.app.notification.controller;

import com.ecofood.app.notification.service.NotificationReadService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationReadController {
  private final NotificationReadService service;
  @GetMapping("/unread-count") public Map<String, Long> unreadCount() { return Map.of("count", service.unreadCount()); }
  @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markRead(@PathVariable String id) { service.markRead(id); }
}
