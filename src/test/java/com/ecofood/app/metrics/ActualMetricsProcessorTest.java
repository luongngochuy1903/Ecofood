package com.ecofood.app.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.metrics.service.*;
import com.ecofood.app.submission.entity.Submission;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class ActualMetricsProcessorTest {
  private final ActualMetricsStore store = mock(ActualMetricsStore.class);
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final ActualMetricsProcessor processor = new ActualMetricsProcessor(store, mongo);

  @Test
  void transientFailurePersistsRetryWithoutOverwritingAnAmbiguouslyCommittedMarker() {
    doThrow(new DataAccessResourceFailureException("timeout")).when(store).process("event", "tenant");
    assertDoesNotThrow(() -> processor.process("event", "tenant"));
    var query = org.mockito.ArgumentCaptor.forClass(Query.class);
    var update = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateFirst(query.capture(), update.capture(), eq(Submission.class));
    assertEquals("tenant", query.getValue().getQueryObject().getString("tenantId"));
    assertEquals(false, query.getValue().getQueryObject().get("actualMetrics.projectedAt", Document.class)
        .get("$exists"));
    assertEquals("RETRY", update.getValue().getUpdateObject().get("$set", Document.class)
        .get("actualMetrics.state"));
    assertFalse(update.getValue().getUpdateObject().get("$set", Document.class)
        .containsKey("actualMetrics.projectedAt"));
  }

  @Test
  void invalidScopeIsBlockedWithoutCreatingMetricsOrRepeatedAutomaticRetries() {
    doThrow(new KpiProcessingException("SUBMISSION_INVOICE_SCOPE_MISMATCH", false))
        .when(store).process("event", "tenant");
    processor.process("event", "tenant");
    var update = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateFirst(any(Query.class), update.capture(), eq(Submission.class));
    assertEquals("BLOCKED", update.getValue().getUpdateObject().get("$set", Document.class)
        .get("actualMetrics.state"));
    assertTrue(update.getValue().getUpdateObject().get("$unset", Document.class)
        .containsKey("actualMetrics.nextRetryAt"));
  }

  @Test
  void successfulProcessingDoesNotPerformASecondNonAtomicCompletionWrite() {
    processor.process("event", "tenant");
    verify(store).process("event", "tenant");
    verifyNoInteractions(mongo);
  }
}
