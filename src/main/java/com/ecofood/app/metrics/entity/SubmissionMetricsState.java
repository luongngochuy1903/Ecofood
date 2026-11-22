package com.ecofood.app.metrics.entity;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A processing-only view of the SAME Submission; its Task 3A business envelope is unchanged. */
@Document("submission")
public record SubmissionMetricsState(@Id String id, Processing actualMetrics) {
  public record Processing(String state, KpiContribution contribution, Instant projectedAt,
                           long projectionRevision, int attempts, Instant nextRetryAt,
                           String lastErrorCode, List<KpiContribution> contributions) {
    public Processing(String state,KpiContribution contribution,Instant projectedAt,long projectionRevision,
        int attempts,Instant nextRetryAt,String lastErrorCode) {
      this(state,contribution,projectedAt,projectionRevision,attempts,nextRetryAt,lastErrorCode,null);
    }
    public List<KpiContribution> entries() { return contributions!=null?contributions:contribution==null?List.of():List.of(contribution); }
  }
}
