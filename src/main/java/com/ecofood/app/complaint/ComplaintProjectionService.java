package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Recovery projection only; no Notification or external delivery is invoked. */
@Service @RequiredArgsConstructor @Slf4j
public class ComplaintProjectionService {
  private final ComplaintRepository complaints;
  private final ComplaintEventRepository events;
  private final ComplaintOutboxRepository outbox;
  private final Clock clock;
  public boolean tryProject(String complaintId) {
    String token = UUID.randomUUID().toString(); Complaint row;
    try { row = complaints.claimProjection(complaintId, token, now()); }
    catch (RuntimeException ex) { log.warn("Complaint projection claim deferred"); return false; }
    if (row == null) return false;
    try {
      var event = row.getPendingCommit().event();
      if (!row.getIdAsString().equals(event.complaintId()) || event.sequence() != row.getVersion() + 1
          || event.toStatus() != row.getStatus() || event.result().version() != row.getVersion()
          || !event.eventId().equals(event.result().eventId()) || !event.complaintId().equals(event.result().complaintId()))
        throw new IllegalStateException("Pending Complaint commit is inconsistent");
      var change = event.processingChange();
      if (change != null && (!Objects.equals(change.toScope(), new com.ecofood.app.complaint.ComplaintContracts.ProcessingScope(row.getProcessingBoundary(), row.getProcessingTenantId()))
          || !Objects.equals(change.toAssigneeUserId(), row.getAssigneeUserId()) || !Objects.equals(event.toQueueId(), row.getCurrentQueueId())))
        throw new IllegalStateException("Pending processing assignment is inconsistent");
      events.append(event);
      for (EventType type : outboxTypes(event)) outbox.append(new DomainEvent(event.eventId() + ":" + type.name(), event.complaintId(), event.sequence(), type, event.occurredAt(), event.correlationId()));
      complaints.acknowledgeProjection(row, token); return true;
    } catch (RuntimeException ex) {
      try { complaints.retryProjection(row, token, now()); } catch (RuntimeException retryFailure) { /* Lease expiry recovers a failed retry write. */ }
      log.warn("Complaint projection deferred; durable pending commit retained"); return false;
    }
  }
  public void recover() {
    try { for (var row : complaints.pending(now())) tryProject(row.getIdAsString()); }
    catch (RuntimeException ex) { log.warn("Complaint projection polling deferred"); }
  }
  static List<EventType> outboxTypes(ComplaintEvent event) {
    if (event.eventType() == EventType.COMPLAINT_STATUS_CHANGED && event.toStatus() == ComplaintContracts.Status.WAITING_REPORTER)
      return List.of(EventType.COMPLAINT_STATUS_CHANGED, EventType.COMPLAINT_INFORMATION_REQUESTED);
    return outboxTypes(event.eventType());
  }
  static List<EventType> outboxTypes(EventType type) {
    return switch (type) {
      case COMPLAINT_RESOLVED, COMPLAINT_CLOSED -> List.of(EventType.COMPLAINT_STATUS_CHANGED, type);
      default -> List.of(type);
    };
  }
  private Instant now() { return Instant.ofEpochMilli(clock.millis()); }
}
