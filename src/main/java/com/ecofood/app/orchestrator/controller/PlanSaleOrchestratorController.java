package com.ecofood.app.orchestrator.controller;

import com.ecofood.app.config.Audited;
import com.ecofood.core.enums.AuditAction;

import com.ecofood.core.dto.PlanSaleOrchestratorDtos;
import com.ecofood.core.dto.PlanSaleOrchestratorView;
import com.ecofood.core.service.PlanSaleOrchestratorService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/propositions/{propositionId}/plans/{planId}/sale-orchestrator")
public class PlanSaleOrchestratorController {
  private final PlanSaleOrchestratorService service;

  @GetMapping
  public ResponseEntity<? extends PlanSaleOrchestratorView> get(
      @PathVariable String propositionId, @PathVariable String planId) {
    return service
        .get(propositionId, planId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @Audited(AuditAction.CREATE)
  @PostMapping
  public ResponseEntity<? extends PlanSaleOrchestratorView> create(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody PlanSaleOrchestratorDtos.Save r) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(propositionId, planId, r));
  }

  @Audited(AuditAction.UPDATE)
  @PutMapping("/draft")
  public PlanSaleOrchestratorView edit(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody PlanSaleOrchestratorDtos.Save r) {
    return service.edit(propositionId, planId, r);
  }

  @Audited(AuditAction.CREATE)
  @PostMapping("/versions")
  public PlanSaleOrchestratorView version(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody PlanSaleOrchestratorDtos.Revision r) {
    return service.version(propositionId, planId, r);
  }

  @GetMapping("/versions")
  public List<PlanSaleOrchestratorDtos.ConfigVersion> versions(
      @PathVariable String propositionId, @PathVariable String planId) {
    return service
        .get(propositionId, planId)
        .map(PlanSaleOrchestratorView::getVersions)
        .orElse(List.of());
  }

  @PostMapping("/validate")
  public PlanSaleOrchestratorDtos.Validation validate(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody PlanSaleOrchestratorDtos.Revision r) {
    return service.validate(propositionId, planId, r);
  }

  @Audited(AuditAction.UPDATE)
  @PostMapping("/activate")
  public PlanSaleOrchestratorView activate(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody PlanSaleOrchestratorDtos.Revision r) {
    return service.activate(propositionId, planId, r);
  }
}
