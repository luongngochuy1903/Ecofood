package com.ecofood.app.orchestrator.controller;

import com.ecofood.app.config.Audited;
import com.ecofood.core.enums.AuditAction;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.PlanSaleOrchestratorDirectoryResponse;
import com.ecofood.core.dto.PlanSaleOrchestratorRequest;
import com.ecofood.core.dto.PlanSaleOrchestratorResponse;
import com.ecofood.core.service.PlanSaleOrchestratorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * No longer nested under a single Plan (an Orchestrator can cover many) —
 * same base path every other directory endpoint in this platform uses (see
 * TenantController), plus plain CRUD routes for one Orchestrator at a time.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/propositions/{propositionId}/sale-orchestrators")
public class PlanSaleOrchestratorController {
  private final PlanSaleOrchestratorService service;

  @GetMapping("/directory")
  public PageResponse<PlanSaleOrchestratorDirectoryResponse> directory(
      @PathVariable String propositionId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "name,asc") String sort,
      @RequestParam(required = false) String search) {
    return service.directory(propositionId, page, size, sort, search);
  }

  @GetMapping("/{id}")
  public PlanSaleOrchestratorResponse get(@PathVariable String propositionId, @PathVariable String id) {
    return service.get(propositionId, id);
  }

  @Audited(AuditAction.CREATE)
  @PostMapping
  public ResponseEntity<PlanSaleOrchestratorResponse> create(
      @PathVariable String propositionId, @Valid @RequestBody PlanSaleOrchestratorRequest r) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(propositionId, r));
  }

  @Audited(AuditAction.UPDATE)
  @PutMapping("/{id}")
  public PlanSaleOrchestratorResponse update(
      @PathVariable String propositionId, @PathVariable String id, @Valid @RequestBody PlanSaleOrchestratorRequest r) {
    return service.update(propositionId, id, r);
  }
}
