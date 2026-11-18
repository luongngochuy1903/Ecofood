package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Append-only history + durable command receipt. No ordinary update/delete repository operations. */
@Document("complaint_event")
public record ComplaintEvent(@Id String eventId, String complaintId, long sequence, EventType eventType,
    ActorType actorType, String actorId, Status fromStatus, Status toStatus, String fromQueueId, String toQueueId,
    String reason, Visibility visibility, Instant occurredAt, String correlationId,
    String commandKey, String payloadFingerprint, CommandResult result, ProcessingChange processingChange) {
  /** Compatibility for existing persisted events and Task 1–3 callers. */
  public ComplaintEvent(String eventId, String complaintId, long sequence, EventType eventType, ActorType actorType, String actorId,
      Status fromStatus, Status toStatus, String fromQueueId, String toQueueId, String reason, Visibility visibility, Instant occurredAt,
      String correlationId, String commandKey, String payloadFingerprint, CommandResult result) {
    this(eventId, complaintId, sequence, eventType, actorType, actorId, fromStatus, toStatus, fromQueueId, toQueueId,
        reason, visibility, occurredAt, correlationId, commandKey, payloadFingerprint, result, null);
  }
  public PublicHistoryEvent publicView() { return new PublicHistoryEvent(eventId, sequence, eventType, fromStatus, toStatus, reason, occurredAt); }
}
