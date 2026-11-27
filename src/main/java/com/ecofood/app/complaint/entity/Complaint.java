package com.ecofood.app.complaint.entity;

import com.ecofood.app.submission.entity.Submission;
import com.ecofood.core.dto.ComplaintDtos;
import com.ecofood.core.dto.ComplaintView;
import com.ecofood.core.enums.ComplaintStatusEnum;
import com.ecofood.core.enums.ComplaintTypeEnum;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One aggregate: transitions, audit and durable handoff commit in the same document. */
@Data
@org.springframework.data.annotation.TypeAlias("com.ecofood.app.complaint.Complaint")
@Document("complaint")
public class Complaint implements ComplaintView {
  @Id private String id;
  private ComplaintTypeEnum complaintType;
  private String customerId, customerTenantId, invoiceId, tenantId, subject, description, assignedTo;
  private ComplaintStatusEnum status;
  private Instant createdAt, updatedAt, resolvedAt, closedAt;
  private long revision;
  @com.fasterxml.jackson.annotation.JsonIgnore private String adminOwnerId;
  @com.fasterxml.jackson.annotation.JsonIgnore private Integer customerComplaintSlot;
  private ComplaintDtos.Queue queue;
  private List<ComplaintDtos.ComplaintEvent> events;
  @com.fasterxml.jackson.annotation.JsonIgnore private List<Handoff> handoffs;
  @org.springframework.data.annotation.TypeAlias("com.ecofood.app.complaint.Complaint$Handoff")
  public record Handoff(Submission submission, boolean pending) {}
}
