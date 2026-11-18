package com.ecofood.app.complaint;

import java.time.Instant;
import java.util.Set;

/** Server-side foundation contracts. These are not HTTP intake DTOs or JWT verification results. */
public final class ComplaintContracts {
  private ComplaintContracts() {}
  public enum Kind { INVOICE, PLATFORM, INTERNAL }
  public enum Status { SUBMITTED, IN_PROGRESS, WAITING_REPORTER, RESOLVED, CLOSED }
  public enum Boundary { TENANT, PLATFORM }
  public enum ActorType { GUEST, CUSTOMER, EMPLOYEE }
  public enum Permission { COMPLAINT_CREATE, COMPLAINT_READ, COMPLAINT_PROCESS, COMPLAINT_ASSIGN,
    COMPLAINT_ESCALATE, COMPLAINT_ROUTING_MANAGE }
  public enum QueueCapability { REPORT, READ, PROCESS, ASSIGN, ESCALATE }
  public enum Authority { NONE, TENANT_AUTHORITY, PLATFORM_AUTHORITY }
  public enum QueuePurpose { REPORTING_ORIGIN, PROCESSING, FINAL_AUTHORITY }
  public enum Publication { DRAFT, PUBLISHED, RETIRED }
  public enum TenantStructure { STANDALONE, MASTER_TENANT, SUB_TENANT }

  /** Built by a trusted authentication adapter, never deserialized from an intake body. */
  public record Actor(ActorType type, String userId, String tenantId, Set<Permission> permissions) {
    public Actor { permissions = permissions == null ? Set.of() : Set.copyOf(permissions); }
  }
  public record Reporter(ActorType type, String userId, String homeTenantId) {}
  public record InvoiceReference(String invoiceId, String tenantId, String distributionPartnerId) {}
  public record ProcessingScope(Boundary boundary, String tenantId) {}
  public record Audit(Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {}

  /** Future aggregate schema. sourceTenantId is immutable and independent of processing scope. */
  public record ComplaintCase(String id, String complaintCode, Kind kind, String categoryCode, String subject, String description,
      Reporter reporter, InvoiceReference invoice, String sourceTenantId, String reporterOriginQueueId, ProcessingScope processing,
      String assignedQueueId, String assignedUserId, String routingPolicyId, int routingPolicyVersion,
      Status status, long version, Audit audit) {}

  /** Produced only after Receipt/claimed-Invoice validation by Task 2; raw tokens are excluded. */
  public record VerifiedInvoice(String invoiceId, String tenantId, String distributionPartnerId) {}
  /** Produced only by a hash/expiry/revocation-checking Guest access adapter, not from request fields. */
  public record VerifiedGuestAccess(String complaintId, Instant expiresAt) {}
  public record RoutingDecision(String policyId, int policyVersion, String queueId, ProcessingScope processing, String reporterOriginQueueId) {}
  public record TenantInfo(String id, TenantStructure structure, String masterTenantId, boolean platform, boolean active) {}
}
