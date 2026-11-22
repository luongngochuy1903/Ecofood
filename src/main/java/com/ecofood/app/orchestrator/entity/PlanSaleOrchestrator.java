package com.ecofood.app.orchestrator.entity;

import com.ecofood.core.dto.PlanSaleOrchestratorDtos;
import com.ecofood.core.dto.PlanSaleOrchestratorView;
import java.time.Instant;
import java.util.*;
import lombok.Data;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.mapping.Document;

/** One document per (Tenant, Proposition, Plan). Config changes never mutate the Plan. */
@Data
@org.springframework.data.annotation.TypeAlias("com.ecofood.app.orchestrator.PlanSaleOrchestrator")
@Document("orchestrator")
public class PlanSaleOrchestrator implements PlanSaleOrchestratorView {
  @Id private String id;
  @Version private Long revision;
  private String tenantId, propositionId, planId;
  private Integer activeVersion;
  private Instant createdAt, updatedAt;
  private String createdBy, updatedBy;
  private List<PlanSaleOrchestratorDtos.ConfigVersion> versions = new ArrayList<>();
  private List<PlanSaleOrchestratorDtos.Audit> audit = new ArrayList<>();

}
