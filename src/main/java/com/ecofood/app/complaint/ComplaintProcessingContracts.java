package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.math.BigDecimal;
import com.ecofood.core.dto.InvoiceItemDto;

/** HTTP processing contracts; queue, Tenant, status, policy and visibility are server-controlled. */
public final class ComplaintProcessingContracts {
  private ComplaintProcessingContracts() {}
  public record Action(@NotBlank @Size(max=128) String requestId, @NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Size(max=1000) String reason) implements ComplaintIntakeRequests.Strict {}
  public record Assignment(@NotBlank @Size(max=128) String requestId, @NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Size(max=1000) String reason, @NotBlank @Pattern(regexp="[a-f0-9]{24}") String assigneeUserId) implements ComplaintIntakeRequests.Strict {
    Action action() { return new Action(requestId, expectedVersion, reason); }
  }
  public record Summary(String complaintId, String complaintCode, Kind kind, String categoryCode, String subject, Status status,
      long version, Boundary processingBoundary, String processingTenantId, String queueId, String assigneeUserId,
      String invoiceId, String distributionPartnerId, Instant submittedAt, Instant updatedAt) {}
  public record Detail(Summary complaint, String description, ActorType reporterType, boolean identityVerified,
      InvoiceReference invoice, List<InvoiceItemReference> invoiceItemReferences, String routingPolicyId, int routingPolicyVersion,
      Instant resolvedAt, Capabilities capabilities, InvoiceView invoiceData, String categoryName, String queueName, String assigneeName) {}
  public record Candidate(String userId, String displayName) {}
  public record Capabilities(long version, List<String> allowedActions, List<Candidate> assignees) {}
  public record InvoiceView(String invoiceCode, Instant transactionTime, String currency, BigDecimal totalAmount, List<InvoiceItemDto> items, String submittedBy) {}
  public record Choice(String value, String label) {}
  public record InboxOptions(Boundary boundary, String tenantId, List<Choice> queues, List<Choice> categories) {}
  public record Search(String complaintCode, String invoiceCode, String categoryCode, String queueId) {}
  public record InboxSummary(String complaintId, String complaintCode, Kind kind, String categoryCode, String subject, Status status,
      long version, Boundary processingBoundary, String processingTenantId, String queueId, String assigneeUserId,
      String invoiceId, String distributionPartnerId, Instant submittedAt, Instant updatedAt,
      String invoiceCode, String queueName, String assigneeName, String categoryName, String sourceTenantId) {
    InboxSummary(Summary s, String code, String queue, String assignee, String category, String source) {
      this(s.complaintId(), s.complaintCode(), s.kind(), s.categoryCode(), s.subject(), s.status(), s.version(), s.processingBoundary(), s.processingTenantId(), s.queueId(), s.assigneeUserId(), s.invoiceId(), s.distributionPartnerId(), s.submittedAt(), s.updatedAt(), code, queue, assignee, category, source);
    }
  }
  public record HistoryEntry(String eventId, long sequence, EventType eventType, ActorType actorType, String actorId,
      Status fromStatus, Status toStatus, String fromQueueId, String toQueueId, String reason, Visibility visibility,
      Instant occurredAt, ProcessingChange processingChange) {}
}
