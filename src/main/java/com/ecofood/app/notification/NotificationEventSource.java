package com.ecofood.app.notification;

import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import java.util.Map;

/** New business actions can supply an adapter; no listeners on arbitrary MongoDB CRUD. */
public interface NotificationEventSource {
  String event();
  Context resolve(Submission event);
  record Context(Invoice invoice, boolean workflowEnabled, Map<String, String> values) {}
}
