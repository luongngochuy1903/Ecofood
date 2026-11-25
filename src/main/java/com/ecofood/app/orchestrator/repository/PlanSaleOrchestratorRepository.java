package com.ecofood.app.orchestrator.repository;

import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import java.util.*;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlanSaleOrchestratorRepository
    extends MongoRepository<PlanSaleOrchestrator, String> {
  List<PlanSaleOrchestrator> findByTenantIdAndPropositionId(String tenantId, String propositionId);

  Optional<PlanSaleOrchestrator> findByIdAndTenantIdAndPropositionId(
      String id, String tenantId, String propositionId);

  Optional<PlanSaleOrchestrator> findByTenantIdAndPlanIdsContaining(String tenantId, String planId);

  /** Enterprise-wide reads only — an Enterprise actor owns the whole Proposition, so it sees every Tenant's own Orchestrators, not only ones under its own tenantId. */
  List<PlanSaleOrchestrator> findByPropositionId(String propositionId);

  Optional<PlanSaleOrchestrator> findByIdAndPropositionId(String id, String propositionId);
}
