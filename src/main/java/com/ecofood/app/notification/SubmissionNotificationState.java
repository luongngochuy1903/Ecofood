package com.ecofood.app.notification;

import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Processing view of the existing business event, not another event collection. */
@Data
@Document("submission")
public class SubmissionNotificationState {
  @Id private String id;
  private String tenantId;
  private Processing notification;
  @Data public static class Processing {
    private String state, leaseToken, result, lastError;
    private Instant leaseUntil, nextAttemptAt, completedAt;
    private int attempts;
    private List<Notification> plan;
  }
}
