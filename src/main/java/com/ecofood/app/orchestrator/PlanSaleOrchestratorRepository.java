package com.ecofood.app.orchestrator;

import java.util.*;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlanSaleOrchestratorRepository
    extends MongoRepository<PlanSaleOrchestrator, String> {
  Optional<PlanSaleOrchestrator> findByTenantIdAndPropositionIdAndPlanId(
      String tenantId, String propositionId, String planId);

  Optional<PlanSaleOrchestrator> findByIdAndTenantId(String id, String tenantId);

  List<PlanSaleOrchestrator> findByTenantIdAndActiveVersionNotNull(String tenantId);
}
