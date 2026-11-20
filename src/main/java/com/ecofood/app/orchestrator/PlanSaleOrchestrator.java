package com.ecofood.app.orchestrator;

import com.ecofood.core.dto.SalePayloadSchema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.*;
import lombok.Data;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.mapping.Document;

/** One document per (Tenant, Proposition, Plan). Config changes never mutate the Plan. */
@Data
@Document("orchestrator")
public class PlanSaleOrchestrator {
  @Id private String id;
  @Version private Long revision;
  private String tenantId, propositionId, planId;
  private Integer activeVersion;
  private Instant createdAt, updatedAt;
  private String createdBy, updatedBy;
  private List<ConfigVersion> versions = new ArrayList<>();
  private List<Audit> audit = new ArrayList<>();

  public enum Status {
    DRAFT,
    ACTIVE,
    ARCHIVED
  }

  public enum Workflow {
    CREATE_OR_LINK_CUSTOMER,
    SEND_EMAIL_NOTIFICATION
  }

  @JsonIgnoreProperties("posBindings")
  public record Configuration(
      String name,
      String description,
      int maxItems,
      SalePayloadSchema payloadSchema,
      Set<Workflow> postWorkflows) {
    public Configuration {
      postWorkflows = postWorkflows == null ? Set.of() : Set.copyOf(postWorkflows);
    }
  }

  public record ConfigVersion(
      int version,
      Status status,
      Configuration configuration,
      Instant createdAt,
      String createdBy,
      Instant updatedAt,
      String updatedBy,
      Instant activatedAt,
      String activatedBy) {}

  public record Audit(String action, int version, Instant at, String actor) {}
}
