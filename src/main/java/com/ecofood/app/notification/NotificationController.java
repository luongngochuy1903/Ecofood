package com.ecofood.app.notification;

import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
  private final NotificationManagement service;
  @GetMapping("/capabilities") public Map<String, Object> capabilities() { return service.capabilities(); }
  @GetMapping("/options") public Map<String, Object> options() { return service.options(); }
  @GetMapping("/rules") public List<NotificationRule> rules() { return service.rules(); }
  @GetMapping("/rules/{id}") public NotificationRule rule(@PathVariable String id) { return service.rule(id); }
  @PostMapping("/rules") @ResponseStatus(HttpStatus.CREATED)
  public NotificationRule create(@RequestBody NotificationManagement.RuleRequest input) { return service.save(null, input); }
  @PutMapping("/rules/{id}") public NotificationRule update(@PathVariable String id, @RequestBody NotificationManagement.RuleRequest input) { return service.save(id, input); }
  @PutMapping("/rules/{id}/enabled") public NotificationRule enable(@PathVariable String id, @RequestBody NotificationManagement.EnabledRequest input) { return service.enable(id, input); }
  @GetMapping public NotificationManagement.Page<Notification> history(@RequestParam(required = false) String event,
      @RequestParam(required = false) String status, @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) { return service.history(event, status, from, to, page, size); }
  @GetMapping("/{id}") public Notification detail(@PathVariable String id) { return service.detail(id); }
  @PostMapping("/{id}/retry") public Notification retry(@PathVariable String id, @RequestBody NotificationManagement.RetryRequest input) { return service.retry(id, input); }
}
