package com.ecofood.app.orchestrator.entity;

import com.ecofood.core.dto.SaleConfigurationField;
import java.time.Instant;
import java.util.*;
import lombok.Data;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One document per Sale Orchestrator — covers many Plans at once (a Plan
 * belongs to at most one Orchestrator per Tenant+Proposition, enforced by a
 * unique index on tenantId+planIds). No version/status lifecycle: just a
 * current, always-editable configuration, same shape as a Draft Plan.
 *
 * <p>Three field lists together describe this Orchestrator's whole
 * submit-transaction shape — see PlanSaleOrchestratorServiceImpl for
 * defaults/validation: {@code planItems} (the item's own
 * planId/quantity/actualPrice — always exactly those three keys),
 * {@code additionalInfoFields} (free-form extra item keys), and
 * {@code invoiceHolder} (the buyer's own info, once per invoice).
 */
@Data
@org.springframework.data.annotation.TypeAlias("com.ecofood.app.orchestrator.PlanSaleOrchestrator")
@Document("orchestrator")
public class PlanSaleOrchestrator {
  @Id private String id;
  @Version private Long revision;
  private String tenantId, propositionId;
  private String name, description;
  private List<String> planIds = new ArrayList<>();
  private List<SaleConfigurationField> planItems = new ArrayList<>();
  private List<SaleConfigurationField> additionalInfoFields = new ArrayList<>();
  private List<SaleConfigurationField> invoiceHolder = new ArrayList<>();
  /** When true, invoiceHolder always carries a locked, required externalId field used to auto-link a submit-transaction's Invoice to a Customer. */
  private boolean createCustomer;
  private Instant createdAt, updatedAt;
  private String createdBy, updatedBy;
}
