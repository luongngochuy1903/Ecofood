package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.PublicHistoryEvent;
import java.time.Instant;
import java.util.List;

public final class ComplaintIntakeResponses {
  private ComplaintIntakeResponses() {}
  public record Category(String code, String name) {}
  public record Item(String invoiceItemId, String productName) {}
  public record Options(List<Category> categories, List<Item> items, String eligibility) {}
  public record Confirmation(String complaintId, String complaintCode, Status status, Instant submittedAt) {}
  public record GuestConfirmation(Confirmation complaint, String trackingUrl, Instant accessExpiresAt) {}
  public record Summary(String complaintId, String complaintCode, Kind kind, String categoryCode, String subject, Status status, Instant submittedAt, String invoiceId) {}
  public record Detail(Summary complaint, String description, List<PublicHistoryEvent> history, int historyPage, int historyTotalPages, long historyTotalElements) {}
}
