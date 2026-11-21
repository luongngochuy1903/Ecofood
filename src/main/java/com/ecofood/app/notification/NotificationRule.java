package com.ecofood.app.notification;

import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document("notification_rule")
public class NotificationRule {
  @Id private String id;
  private String tenantId;
  private String name;
  private String eventType;
  private String channel = "EMAIL";
  private boolean enabled;
  private Recipients recipients;
  private String subject;
  private String content;
  private long revision;
  private Instant createdAt;
  private Instant updatedAt;
  private String createdBy;
  private String updatedBy;
  // Old deliveries retain their rendered content and rule revision.
  private List<Change> audit;
  public record Recipients(List<String> userIds, List<String> roles, List<String> permissions,
                           boolean invoiceCustomer, List<String> emails, Boolean complaintCustomer) {
    public Recipients(List<String> userIds, List<String> roles, List<String> permissions, boolean invoiceCustomer, List<String> emails) {
      this(userIds,roles,permissions,invoiceCustomer,emails,false);
    }
  }
  public record Change(long revision, Instant at, String actor, String action) {}
}
