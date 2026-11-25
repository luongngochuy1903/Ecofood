package com.ecofood.app.notification.controller;

import com.ecofood.app.config.Audited;
import com.ecofood.core.enums.AuditAction;

import com.ecofood.core.dto.NotificationDtos;
import com.ecofood.core.dto.NotificationRuleView;
import com.ecofood.core.dto.NotificationView;
import com.ecofood.core.service.NotificationManagementService;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
  private final NotificationManagementService service;
  @GetMapping("/capabilities") public Map<String, Object> capabilities() { return service.capabilities(); }
  @GetMapping("/options") public Map<String, Object> options() { return service.options(); }
  @GetMapping("/rules") public List<? extends NotificationRuleView> rules() { return service.rules(); }
  @GetMapping("/rules/{id}") public NotificationRuleView rule(@PathVariable String id) { return service.rule(id); }
  @Audited(AuditAction.CREATE)
  @PostMapping("/rules") @ResponseStatus(HttpStatus.CREATED)
  public NotificationRuleView create(@RequestBody NotificationDtos.RuleRequest input) { return service.save(null, input); }
  @Audited(AuditAction.UPDATE)
  @PutMapping("/rules/{id}") public NotificationRuleView update(@PathVariable String id, @RequestBody NotificationDtos.RuleRequest input) { return service.save(id, input); }
  @Audited(AuditAction.UPDATE)
  @PutMapping("/rules/{id}/enabled") public NotificationRuleView enable(@PathVariable String id, @RequestBody NotificationDtos.EnabledRequest input) { return service.enable(id, input); }
  @GetMapping public NotificationDtos.Page<? extends NotificationView> history(@RequestParam(required = false) String event,
      @RequestParam(required = false) String status, @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) { return service.history(event, status, from, to, page, size); }
  @GetMapping("/{id}") public NotificationView detail(@PathVariable String id) { return service.detail(id); }
  @Audited(AuditAction.UPDATE)
  @PostMapping("/{id}/retry") public NotificationView retry(@PathVariable String id, @RequestBody NotificationDtos.RetryRequest input) { return service.retry(id, input); }
}
