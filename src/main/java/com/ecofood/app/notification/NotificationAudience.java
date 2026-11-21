package com.ecofood.app.notification;

import com.ecofood.app.submission.entity.Submission;
import java.util.List;

/** Source-specific recipient authorization; existing Invoice audience remains unchanged. */
public interface NotificationAudience {
  boolean supports(String event);
  void validate(String event, NotificationRule.Recipients recipients, String tenant);
  List<NotificationRecipients.Recipient> resolve(NotificationRule rule, Submission event);
  boolean stillAllowed(Notification notification);
}
