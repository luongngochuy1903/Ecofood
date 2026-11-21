package com.ecofood.app.notification.entity;

import com.ecofood.core.dto.NotificationDtos;
import com.ecofood.core.dto.NotificationRuleView;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@org.springframework.data.annotation.TypeAlias("com.ecofood.app.notification.NotificationRule")
@Document("notification_rule")
public class NotificationRule implements NotificationRuleView {
  @Id private String id;
  private String tenantId;
  private String name;
  private String eventType;
  private String channel = "EMAIL";
  private boolean enabled;
  private NotificationDtos.Recipients recipients;
  private String subject;
  private String content;
  private long revision;
  private Instant createdAt;
  private Instant updatedAt;
  private String createdBy;
  private String updatedBy;
  // Old deliveries retain their rendered content and rule revision.
  private List<NotificationDtos.Change> audit;
}
