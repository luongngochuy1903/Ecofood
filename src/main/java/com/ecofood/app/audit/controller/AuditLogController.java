package com.ecofood.app.audit.controller;

import com.ecofood.core.dto.AuditLogDtos.*;
import com.ecofood.core.service.AuditLogService;
import com.ecofood.core.enums.AuditAction;
import com.ecofood.core.enums.AuditOutcome;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-events")
@RequiredArgsConstructor
public class AuditLogController {
  private final AuditLogService service;

  @GetMapping public Page list(@RequestParam(required = false) String actorId,
      @RequestParam(required = false) AuditAction action, @RequestParam(required = false) String module,
      @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
      @RequestParam(required = false) AuditOutcome outcome, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return service.list(new Filter(actorId, action, module, from, to, outcome, page, size));
  }
  @GetMapping("/{id}") public Entry detail(@PathVariable String id) { return service.detail(id); }
  @GetMapping("/actors") public List<String> actors(@RequestParam(defaultValue = "") String prefix) {
    return service.actors(prefix);
  }
}
