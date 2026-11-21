package com.ecofood.app.complaint;

import com.ecofood.app.submission.entity.Submission;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One aggregate: transitions, audit and durable handoff commit in the same document. */
@Data
@Document("complaint")
public class Complaint {
  public enum Type { TENANT, ECOSYSTEM }
  public enum Status { SUBMITTED, IN_PROGRESS, WAITING_REPORTER, RESOLVED, CLOSED }
  @Id private String id;
  private Type complaintType;
  private String customerId, customerTenantId, invoiceId, tenantId, subject, description, assignedTo;
  private Status status;
  private Instant createdAt, updatedAt, resolvedAt, closedAt;
  private long revision;
  private Queue queue;
  private List<ComplaintEvent> events;
  @com.fasterxml.jackson.annotation.JsonIgnore private List<Handoff> handoffs;
  public record Queue(String tenantId, String role) {}
  public record ComplaintEvent(String id, String complaintId, String actorId, String eventType,
      Status previousStatus, Status newStatus, Instant createdAt, String note) {}
  public record Handoff(Submission submission, boolean pending) {}
}
