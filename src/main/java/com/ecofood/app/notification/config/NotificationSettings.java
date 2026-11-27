package com.ecofood.app.notification.config;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NotificationSettings {
  public final String from;
  public final boolean deliveryEnabled;
  public final int maxAttempts;
  private final String approvedRecipients;
  public NotificationSettings(@Value("${ecofood.notification.from:}") String from,
      @Value("true") boolean enabled,
      @Value("${ecofood.notification.max-attempts:5}") int max,
      @Value("${ecofood.notification.approved-recipients:}") String approved) {
    // Keep the constructor shape for callers; delivery no longer has an off switch.
    this.from = from; this.deliveryEnabled = true;
    this.maxAttempts = Math.max(1, Math.min(10, max)); this.approvedRecipients = approved;
  }
  /** Deployment-approved exact addresses, isolated by tenant: tenantId=email,email;tenantId=email. */
  public Set<String> approved(String tenant) {
    Set<String> result = new TreeSet<>();
    for (String entry : approvedRecipients.split(";")) {
      String[] pair = entry.split("=", 2);
      if (pair.length == 2 && pair[0].trim().equals(tenant))
        for (String email : pair[1].split(",")) if (validEmail(email.trim())) result.add(canonical(email));
    }
    return result;
  }
  public static String canonical(String email) { return email.trim().toLowerCase(Locale.ROOT); }
  public static boolean validEmail(String value) {
    return value != null && value.length() <= 254
        && value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
  }
}
