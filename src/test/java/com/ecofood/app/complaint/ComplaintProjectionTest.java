package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ComplaintProjectionTest {
  final ComplaintCoreFixture f = new ComplaintCoreFixture();
  final ComplaintRepository complaints = mock(ComplaintRepository.class);
  final ComplaintEventRepository events = mock(ComplaintEventRepository.class);
  final ComplaintOutboxRepository outbox = mock(ComplaintOutboxRepository.class);
  final ComplaintProjectionService projection = new ComplaintProjectionService(complaints, events, outbox, f.clock);
  Complaint pending() {
    var row = f.row(); row.setPendingCommit(new PendingCommit(f.event(row, Visibility.PUBLIC)));
    when(complaints.claimProjection(eq(row.getIdAsString()), anyString(), any())).thenReturn(row); return row;
  }
  @Test void noCommittedSourceOrNoLeaseProducesNoHistoryOrOutbox() {
    assertFalse(projection.tryProject(ComplaintCoreFixture.CASE)); verifyNoInteractions(events, outbox);
  }
  @Test void projectionOrdersHistoryBeforeOutboxAndAck() {
    var row = pending(); assertTrue(projection.tryProject(row.getIdAsString()));
    var order = inOrder(events, outbox, complaints); order.verify(complaints).claimProjection(eq(row.getIdAsString()), anyString(), any());
    order.verify(events).append(row.getPendingCommit().event()); order.verify(outbox).append(any()); order.verify(complaints).acknowledgeProjection(eq(row), anyString());
  }
  @Test void eventFailureRetainsIntentAndNeverAcknowledges() {
    var row = pending(); doThrow(new IllegalStateException("failure")).when(events).append(any());
    assertFalse(projection.tryProject(row.getIdAsString())); verifyNoInteractions(outbox);
    verify(complaints, never()).acknowledgeProjection(any(), anyString()); verify(complaints).retryProjection(eq(row), anyString(), any());
  }
  @Test void outboxFailureCannotAcknowledgeAndRetryWriteFailureIsRecoverableByLeaseExpiry() {
    var row = pending(); doThrow(new IllegalStateException("failure")).when(outbox).append(any());
    doThrow(new IllegalStateException("retry write failed")).when(complaints).retryProjection(any(), anyString(), any());
    assertFalse(projection.tryProject(row.getIdAsString())); verify(complaints, never()).acknowledgeProjection(any(), anyString());
  }
  @Test void inconsistentPendingCommitIsNotProjected() {
    var row = pending(); row.setVersion(3L); assertFalse(projection.tryProject(row.getIdAsString())); verifyNoInteractions(events, outbox);
  }
  @Test void resolvedAndClosedEmitStableGenericAndSpecificDomainEventTypes() {
    assertEquals(List.of(EventType.COMPLAINT_STATUS_CHANGED, EventType.COMPLAINT_RESOLVED), ComplaintProjectionService.outboxTypes(EventType.COMPLAINT_RESOLVED));
    assertEquals(List.of(EventType.COMPLAINT_STATUS_CHANGED, EventType.COMPLAINT_CLOSED), ComplaintProjectionService.outboxTypes(EventType.COMPLAINT_CLOSED));
    assertEquals(List.of(EventType.COMPLAINT_ASSIGNED), ComplaintProjectionService.outboxTypes(EventType.COMPLAINT_ASSIGNED));
    assertEquals(List.of(EventType.COMPLAINT_ESCALATED), ComplaintProjectionService.outboxTypes(EventType.COMPLAINT_ESCALATED));
    var row = pending(); projection.tryProject(row.getIdAsString()); var event = ArgumentCaptor.forClass(DomainEvent.class); verify(outbox).append(event.capture());
    assertEquals(row.getPendingCommit().event().eventId() + ":COMPLAINT_SUBMITTED", event.getValue().eventId());
  }
}
