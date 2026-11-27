package com.ecofood.app.notification.entity;

import com.ecofood.core.dto.NotificationView;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Queue and delivery history share one record; no parallel email outbox. */
@Data
@org.springframework.data.annotation.TypeAlias("com.ecofood.app.notification.Notification")
@Document("notification")
public class Notification implements NotificationView {
  @Id private String id;
  private String submissionId;
  @org.springframework.data.annotation.Transient private String complaintId;
  private String tenantId;
  private String ruleId;
  private String type;
  private String channel = "EMAIL";
  private long ruleRevision;
  private String content;
  private String subject;
  private String sendFrom;
  private String sendTo;
  private String recipientUserId;
  private String status = "PENDING";
  private Instant createdAt;
  private Instant sentAt;
  private Instant nextAttemptAt;
  private Instant leaseUntil;
  private int retryCount;
  private int totalAttempts;
  private String lastError;
  private boolean deliveryUncertain;
  private String manualRetryBy;
  private Instant manualRetryAt;
  @JsonIgnore private String leaseToken;
}
