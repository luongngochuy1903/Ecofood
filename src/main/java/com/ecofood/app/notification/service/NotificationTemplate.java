package com.ecofood.app.notification.service;

import java.util.*;
import java.util.regex.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Plain text only. Dynamic values never become HTML or SMTP headers. */
@Component
public class NotificationTemplate {
  public static final Set<String> INVOICE_FIELDS = Set.of("invoiceNumber", "propositionId", "planId", "totalAmount", "currency", "receivedAt", "submissionId", "eventType", "tenantId");
  public static final Set<String> COMPLAINT_FIELDS = Set.of("submissionId", "eventType", "tenantId", "complaintId", "complaintSubject", "complaintType", "complaintStatus", "resolution", "complaintDescription");
  public static final Set<String> FIELDS = Set.of("invoiceNumber", "propositionId", "planId",
      "totalAmount", "currency", "receivedAt", "submissionId", "eventType", "tenantId",
      "complaintId", "complaintSubject", "complaintType", "complaintStatus", "resolution", "complaintDescription");
  private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
  public void validateForEvent(String event, String subject, String content) {
    validate(subject,content);
    var fields = "INVOICE_PAID".equals(event) ? INVOICE_FIELDS : COMPLAINT_FIELDS;
    for (String text : List.of(subject,content)) {
      Matcher matcher = TOKEN.matcher(text);
      while (matcher.find()) if (!fields.contains(matcher.group(1)) || ("COMPLAINT_CREATED".equals(event) && "resolution".equals(matcher.group(1))))
        throw bad("Placeholder unavailable for selected event: " + matcher.group(1));
    }
  }
  public void validate(String subject, String content) {
    if (subject == null || subject.isBlank() || subject.length() > 200 || subject.contains("\r") || subject.contains("\n")
        || content == null || content.isBlank() || content.length() > 10000)
      throw bad("Subject/content không hợp lệ");
    for (String text : List.of(subject, content)) {
      Matcher matcher = TOKEN.matcher(text);
      while (matcher.find()) if (!FIELDS.contains(matcher.group(1))) throw bad("Placeholder không hỗ trợ: " + matcher.group(1));
      if (TOKEN.matcher(text).replaceAll("").matches("(?s).*[{}].*")) throw bad("Placeholder không hợp lệ");
    }
  }
  public String render(String text, Map<String, String> values, boolean header) {
    Matcher matcher = TOKEN.matcher(text);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      String value = values.get(matcher.group(1));
      if (value == null || value.isBlank()) throw new IllegalStateException("TEMPLATE_DATA_MISSING");
      if (header) value = value.replace('\r', ' ').replace('\n', ' ');
      matcher.appendReplacement(result, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(result); return result.toString();
  }
  static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
