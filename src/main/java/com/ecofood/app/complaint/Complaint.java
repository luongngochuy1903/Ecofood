package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.core.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter @Setter @Document("complaint")
public class Complaint extends BaseEntity {
  private String complaintCode;
  private Kind kind;
  private int schemaVersion = 1;
  @Version private Long version;
  private String categoryCode;
  private String subject;
  private String description;
  private ActorType reporterType;
  private String reporterUserId;
  private String reporterHomeTenantId;
  private boolean identityVerified;
  private String invoiceId;
  private String invoiceTenantId;
  private String invoiceSubmittedBy;
  private String distributionPartnerId;
  private List<InvoiceItemReference> invoiceItemReferences = List.of();
  private String originTenantId;
  private String reporterOriginQueueId;
  private Status status;
  private Boundary processingBoundary;
  private String processingTenantId;
  private String currentQueueId;
  private String assigneeUserId;
  private String routingPolicyId;
  private int routingPolicyVersion;
  private RoutingState routingState;
  @JsonIgnore private RoutingDecision submittedRouting;
  private Instant submittedAt;
  private Instant resolvedAt;
  private Instant closedAt;
  private String correlationId;
  @JsonIgnore private String submissionKey;
  @JsonIgnore private String submissionFingerprint;
  @JsonIgnore private CommandResult submissionResult;
  /** One durable command/event intent; new writes wait for projection instead of growing an array. */
  @JsonIgnore private PendingCommit pendingCommit;
  @JsonIgnore private String projectionLeaseToken;
  @JsonIgnore private Instant projectionLeaseUntil;
  @JsonIgnore private Instant nextProjectionAt;
  @JsonIgnore private int projectionAttempts;
  @JsonIgnore private String lastProjectionError;

  public ComplaintCase asCase() {
    return new ComplaintCase(getIdAsString(), complaintCode, kind, categoryCode, subject, description,
        new Reporter(reporterType, reporterUserId, reporterHomeTenantId), invoiceId == null ? null : new InvoiceReference(invoiceId, invoiceTenantId, distributionPartnerId),
        kind == Kind.INVOICE ? invoiceTenantId : originTenantId, reporterOriginQueueId, new ProcessingScope(processingBoundary, processingTenantId),
        currentQueueId, assigneeUserId, routingPolicyId, routingPolicyVersion, status, version,
        new Audit(getCreatedAt(), getCreatedBy(), getUpdatedAt(), getUpdatedBy()));
  }
}
