package com.ecofood.app.complaint;

import java.util.List;
import jakarta.validation.constraints.*;

public final class ComplaintDtos {
  private ComplaintDtos() {}
  // No customerId, tenantId, status, route or assignee is accepted from Customer.
  public record Create(@NotNull Complaint.Type complaintType, String invoiceId,
      @NotBlank @Size(max=200) String subject, @NotBlank @Size(max=10000) String description,
      @NotBlank String requestId) {}
  public record Action(@NotNull Long revision, @NotBlank @Size(max=4000) String note,
      Complaint.Status status, String assignedTo) {}
  public record Reply(@NotNull Long revision, @NotBlank @Size(max=4000) String note) {}
  public record Page<T>(List<T> content, int page, int size, boolean hasNext) {}
  public record Assignee(String id, String name) {}
  public record Capabilities(String tenantId, List<Complaint.Type> types, boolean manage, boolean assign) {}
  public record Detail(Complaint complaint, String customerName, String invoiceNumber) {}
}
