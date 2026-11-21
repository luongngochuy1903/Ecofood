package com.ecofood.app.notification.integration;

import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRule;
import com.ecofood.app.notification.service.NotificationRecipients;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.core.dto.NotificationDtos;
import java.util.List;

/** Source-specific recipient authorization; existing Invoice audience remains unchanged. */
public interface NotificationAudience {
  boolean supports(String event);
  void validate(String event, NotificationDtos.Recipients recipients, String tenant);
  List<NotificationRecipients.Recipient> resolve(NotificationRule rule, Submission event);
  boolean stillAllowed(Notification notification);
}
