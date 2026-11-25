package com.ecofood.app.metrics.service;

import com.ecofood.app.submission.entity.Submission;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Legacy projection processor, retained for compatibility tests; no longer registered or scheduled. */
@Deprecated
@RequiredArgsConstructor
@Slf4j
public class ActualMetricsProcessor {
  private static final String NEXT_RETRY_AT = "actualMetrics.nextRetryAt";
  private static final String METRICS_STATE = "actualMetrics.state";
  private final ActualMetricsStore store;
  private final MongoTemplate mongo;

  public void process(String submissionId, String tenantId) {
    try { store.process(submissionId, tenantId); }
    catch (RuntimeException ex) {
      boolean retryable = !(ex instanceof KpiProcessingException problem) || problem.retryable();
      String code = ex instanceof KpiProcessingException problem ? problem.code() : "METRICS_TRANSACTION_FAILED";
      log.warn("KPI processing deferred for {} ({})", submissionId, code);
      try {
        // Never overwrite a committed marker after an ambiguous commit/late failure.
        var query = Query.query(Criteria.where("_id").is(submissionId).and("tenantId").is(tenantId)
            .and("actualMetrics.projectedAt").exists(false));
        var update = new Update().set(METRICS_STATE, retryable ? "RETRY" : "BLOCKED")
            .set("actualMetrics.lastErrorCode", code).inc("actualMetrics.attempts", 1);
        if (retryable) update.set(NEXT_RETRY_AT, Instant.now().plusSeconds(60));
        else update.unset(NEXT_RETRY_AT);
        mongo.updateFirst(query, update, Submission.class);
      } catch (RuntimeException bookkeepingFailure) {
        log.warn("KPI retry metadata deferred for {} ({})", submissionId,
            bookkeepingFailure.getClass().getSimpleName());
      }
    }
  }

  public void recover() {
    try {
      var due = new Criteria().orOperator(
          Criteria.where(METRICS_STATE).exists(false),
          new Criteria().andOperator(Criteria.where(METRICS_STATE).is("RETRY"),
              Criteria.where(NEXT_RETRY_AT).lte(Instant.now())));
      var query = Query.query(new Criteria().andOperator(
          Criteria.where("event").is(Submission.INVOICE_PAID), Criteria.where("sourceType").in("INVOICE","INVOICE_BATCH"), due))
          .with(Sort.by("createdAt", "id")).limit(100);
      for (var event : mongo.find(query, Submission.class)) process(event.id(), event.tenantId());
    } catch (RuntimeException ex) {
      log.warn("KPI recovery deferred ({})", ex.getClass().getSimpleName());
    }
  }
}
