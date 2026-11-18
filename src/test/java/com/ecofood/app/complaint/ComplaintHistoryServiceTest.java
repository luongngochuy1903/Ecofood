package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ecofood.app.complaint.ComplaintCoreFixture.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.config.ForbiddenException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class ComplaintHistoryServiceTest {
  final ComplaintCoreFixture f = new ComplaintCoreFixture();
  final ComplaintRepository complaints = mock(ComplaintRepository.class);
  final ComplaintEventRepository events = mock(ComplaintEventRepository.class);
  final ComplaintHistoryService history = new ComplaintHistoryService(complaints, events, f.authorization);
  void pending(Visibility visibility) {
    var row = f.row(); row.setPendingCommit(new PendingCommit(f.event(row, visibility)));
    when(complaints.findCommitted(CASE)).thenReturn(Optional.of(row));
    when(events.query(eq(CASE), eq(1L), eq(1L), anyBoolean(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
  }
  @Test void pendingPublicCommitIsVisibleExactlyOnceBeforeProjectionWithoutEmployeeMetadata() {
    pending(Visibility.PUBLIC);
    var page = history.reporter(CASE, f.customer(), null, 0, 10);
    assertEquals(1, page.getTotalElements()); assertEquals(1, page.getContent().size()); assertEquals(1L, page.getContent().get(0).sequence());
    verify(events).query(CASE, 1L, 1L, true, PageRequest.of(0, 10));
    var fields = Arrays.stream(PublicHistoryEvent.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
    assertFalse(fields.contains("actorId")); assertFalse(fields.contains("fromQueueId")); assertFalse(fields.contains("commandKey"));
  }
  @Test void pendingInternalCommitNeverAppearsInReporterHistoryButIsVisibleToAuthorizedHandler() {
    pending(Visibility.INTERNAL);
    assertEquals(0, history.reporter(CASE, f.customer(), null, 0, 10).getTotalElements());
    assertEquals(1, history.processing(CASE, f.handler(), 0, 10).getTotalElements());
    assertThrows(ForbiddenException.class, () -> history.processing(CASE, f.customer(), 0, 10));
  }
  @Test void reporterHistoryRejectsAnUnrelatedCustomerEvenIfCaseIdentifierIsKnown() {
    pending(Visibility.PUBLIC);
    var other = new com.ecofood.app.complaint.ComplaintContracts.Actor(com.ecofood.app.complaint.ComplaintContracts.ActorType.CUSTOMER, OTHER, PORTAL, f.customer().permissions());
    assertThrows(ForbiddenException.class, () -> history.reporter(CASE, other, null, 0, 10)); verifyNoInteractions(events);
  }
}
