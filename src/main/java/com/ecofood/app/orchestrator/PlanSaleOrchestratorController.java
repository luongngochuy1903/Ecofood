package com.ecofood.app.orchestrator;

import com.ecofood.app.orchestrator.PlanSaleOrchestratorService.*;
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
  public ResponseEntity<PlanSaleOrchestrator> get(
      @PathVariable String propositionId, @PathVariable String planId) {
    return service
        .get(propositionId, planId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping
  public ResponseEntity<PlanSaleOrchestrator> create(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody Save r) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(propositionId, planId, r));
  }

  @PutMapping("/draft")
  public PlanSaleOrchestrator edit(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody Save r) {
    return service.edit(propositionId, planId, r);
  }

  @PostMapping("/versions")
  public PlanSaleOrchestrator version(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody Revision r) {
    return service.version(propositionId, planId, r);
  }

  @GetMapping("/versions")
  public List<PlanSaleOrchestrator.ConfigVersion> versions(
      @PathVariable String propositionId, @PathVariable String planId) {
    return service
        .get(propositionId, planId)
        .map(PlanSaleOrchestrator::getVersions)
        .orElse(List.of());
  }

  @PostMapping("/validate")
  public Validation validate(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody Revision r) {
    return service.validate(propositionId, planId, r);
  }

  @PostMapping("/activate")
  public PlanSaleOrchestrator activate(
      @PathVariable String propositionId, @PathVariable String planId, @RequestBody Revision r) {
    return service.activate(propositionId, planId, r);
  }
}
