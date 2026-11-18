package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.notification.NotificationContracts;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Trusted bridge: immutable committed history + durable outbox prove the source, never request data. */
@Service @RequiredArgsConstructor
public class ComplaintNotificationSource {
  private final ComplaintRepository complaints;
  private final ComplaintEventRepository events;
  private final ComplaintOutboxRepository outbox;

  public record Notice(String eventId, String complaintId, String customerId, String homeTenantId,
      String sourceTenantId, String distributionPartnerId, String title, String message) {
    public NotificationContracts.Event notification() {
      return new NotificationContracts.Event(eventId, customerId, sourceTenantId, distributionPartnerId,
          NotificationContracts.Type.COMPLAINT, NotificationContracts.ReferenceType.COMPLAINT, complaintId, NotificationContracts.Channel.IN_APP);
    }
  }

  public Optional<Notice> forEvent(DomainEvent event) {
    if (event == null || !outbox.findEvent(event.eventId()).filter(event::equals).isPresent()) throw invalid();
    var history = events.findById(event.complaintId() + ":" + event.sequence()).orElseThrow(this::invalid);
    if (!ComplaintProjectionService.outboxTypes(history).contains(event.eventType())
        || !event.equals(domain(history, event.eventType()))) throw invalid();
    return notice(history);
  }

  /** Rechecked by UC-016 before persistence; callers cannot invent customer/source/reference context. */
  public Notice forNotification(String eventId, String complaintId) {
    ComplaintValidation.id(complaintId);
    if (eventId == null || !eventId.matches(complaintId + ":[1-9][0-9]{0,17}:COMPLAINT")) throw invalid();
    var history = events.findById(eventId.substring(0, eventId.lastIndexOf(':'))).orElseThrow(this::invalid);
    var original = domain(history, history.eventType());
    if (!outbox.findEvent(original.eventId()).filter(original::equals).isPresent()) throw invalid();
    return notice(history).filter(n -> n.eventId().equals(eventId) && n.complaintId().equals(complaintId)).orElseThrow(this::invalid);
  }

  private Optional<Notice> notice(ComplaintEvent history) {
    var row = complaints.findCommitted(history.complaintId()).filter(c -> !Boolean.TRUE.equals(c.getDeleted())).orElseThrow(this::invalid);
    if (row.getKind() == Kind.INTERNAL || row.getReporterType() != ActorType.CUSTOMER || !row.isIdentityVerified()) return Optional.empty();
    ComplaintValidation.id(row.getReporterUserId()); ComplaintValidation.id(row.getReporterHomeTenantId());
    String source = row.getKind() == Kind.INVOICE ? row.getInvoiceTenantId() : row.getReporterHomeTenantId();
    ComplaintValidation.id(source);
    // Do not copy subject, description, reason, assignee, queue, proof or internal notes into notifications.
    String title = switch (history.eventType()) {
      case COMPLAINT_SUBMITTED -> "Đã tiếp nhận khiếu nại";
      case COMPLAINT_ASSIGNED -> "Khiếu nại đã được phân công";
      case COMPLAINT_ESCALATED -> "Khiếu nại đã được chuyển cấp";
      case COMPLAINT_RESOLVED -> "Khiếu nại đã được giải quyết";
      case COMPLAINT_CLOSED -> "Khiếu nại đã được đóng";
      case COMPLAINT_INFORMATION_REQUESTED -> "Khiếu nại cần bổ sung thông tin";
      case COMPLAINT_STATUS_CHANGED -> history.toStatus() == Status.WAITING_REPORTER ? "Khiếu nại cần bổ sung thông tin" : "Trạng thái khiếu nại đã thay đổi";
    };
    return Optional.of(new Notice(history.eventId() + ":COMPLAINT", history.complaintId(), row.getReporterUserId(), row.getReporterHomeTenantId(),
        source, row.getKind() == Kind.INVOICE ? row.getDistributionPartnerId() : null,
        title, "Có cập nhật cho khiếu nại " + row.getComplaintCode() + ". Mở chi tiết để xem thông tin được phép truy cập."));
  }
  private DomainEvent domain(ComplaintEvent history, EventType type) {
    return new DomainEvent(history.eventId() + ":" + type.name(), history.complaintId(), history.sequence(), type, history.occurredAt(), history.correlationId());
  }
  private IllegalArgumentException invalid() { return new IllegalArgumentException("Complaint notification source unavailable"); }
}
