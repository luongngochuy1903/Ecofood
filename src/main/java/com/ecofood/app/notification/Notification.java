package com.ecofood.app.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Queue and delivery history share one record; no parallel email outbox. */
@Data
@Document("notification")
public class Notification {
  @Id private String id;
  private String submissionId, tenantId, ruleId, type, channel = "EMAIL";
  private long ruleRevision;
  private String content, subject, sendFrom, sendTo, recipientUserId;
  private String status = "PENDING";
  private Instant createdAt, sentAt, nextAttemptAt, leaseUntil;
  private int retryCount, totalAttempts;
  private String lastError;
  private boolean deliveryUncertain;
  private String manualRetryBy;
  private Instant manualRetryAt;
  @JsonIgnore private String leaseToken;
}
