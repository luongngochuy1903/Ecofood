package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import java.time.Instant;
import java.util.*;

/** Server-side commands only. Intake must authenticate Actor/proofs and resolve RoutingDecision first. */
public final class ComplaintCoreContracts {
  private ComplaintCoreContracts() {}
  public enum Visibility { PUBLIC, INTERNAL } // PUBLIC means visible to this case's reporter, never anonymous public access.
  public enum RoutingState { ASSIGNED }
  public enum EventType { COMPLAINT_SUBMITTED, COMPLAINT_STATUS_CHANGED, COMPLAINT_ASSIGNED,
    COMPLAINT_ESCALATED, COMPLAINT_RESOLVED, COMPLAINT_CLOSED, COMPLAINT_INFORMATION_REQUESTED }
  public enum Operation { START_PROCESSING, REQUEST_INFORMATION, RESUME_PROCESSING, RESOLVE, CLOSE, ASSIGN, ESCALATE }
  public enum DispatchState { NEW, CLAIMED, ACKNOWLEDGED }
  public record InvoiceItemReference(int lineIndex, String productCode) {}
  public record SubmitCommand(String requestId, String correlationId, Kind kind, String categoryCode, String subject,
      String description, Actor actor, VerifiedInvoice invoice, List<InvoiceItemReference> invoiceItemReferences,
      RoutingDecision routing, String guestSourceKey) {
    public SubmitCommand { invoiceItemReferences = invoiceItemReferences == null ? List.of() : List.copyOf(invoiceItemReferences); }
  }
  public record WorkflowCommand(String complaintId, long expectedVersion, String requestId, String correlationId,
      Actor actor, VerifiedGuestAccess guestAccess, String reason, Visibility visibility) {}
  public record CommandResult(String complaintId, String complaintCode, Status status, long version,
      String eventId, long sequence, Instant processedAt) {}
  public record PendingCommit(ComplaintEvent event) {}
  public record ProcessingChange(ProcessingScope fromScope, ProcessingScope toScope,
      String fromAssigneeUserId, String toAssigneeUserId, String policyId, int policyVersion) {}
  public record PublicHistoryEvent(String eventId, long sequence, EventType eventType, Status fromStatus,
      Status toStatus, String reason, Instant occurredAt) {}
  public record DomainEvent(String eventId, String complaintId, long sequence, EventType eventType,
      Instant occurredAt, String correlationId) {}
  public record OutboxLease(DomainEvent event, String consumerId, String leaseToken) {}
  public record Filter(Set<Status> statuses, Set<Kind> kinds, Instant from, Instant to) {
    public Filter { statuses = statuses == null ? Set.of() : Set.copyOf(statuses); kinds = kinds == null ? Set.of() : Set.copyOf(kinds); }
    public static Filter all() { return new Filter(Set.of(), Set.of(), null, null); }
  }
  public sealed interface QueryScope permits ReporterScope, QueueScope, GuestCaseScope, InboxScope {}
  public record InboxGrant(String queueId, Kind kind, String sourceTenantId, boolean allInvoices, Set<String> invoiceIds) {
    public InboxGrant { invoiceIds = Set.copyOf(invoiceIds); }
  }
  public record InboxScope(ProcessingScope processing, List<InboxGrant> grants) implements QueryScope {
    public InboxScope { grants = List.copyOf(grants); }
  }
  public record ReporterScope(ActorType reporterType, String userId, String homeTenantId) implements QueryScope {}
  public record QueueScope(ProcessingScope processing, Set<String> queueIds, boolean fullWithinQueues,
      Set<String> limitedComplaintIds) implements QueryScope {
    public QueueScope { queueIds = queueIds == null ? Set.of() : Set.copyOf(queueIds);
      limitedComplaintIds = limitedComplaintIds == null ? Set.of() : Set.copyOf(limitedComplaintIds); }
  }
  /** Construct only after verifying the case-bound Guest capability; never a scope for all Guest cases. */
  public record GuestCaseScope(String complaintId) implements QueryScope {}
}
