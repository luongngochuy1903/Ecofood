package com.ecofood.app.submission.entity;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One shared business event; delivery retries must not allocate a new event identity. */
@Document("submission")
public record Submission(
    @Id String id,
    String event,
    Instant createdAt,
    String tenantId,
    String sourceType,
    String sourceId,
    String propositionId,
    String planId,
    String orchestratorId,
    int orchestratorVersion) {
  public static final String INVOICE_PAID = "INVOICE_PAID";
}
