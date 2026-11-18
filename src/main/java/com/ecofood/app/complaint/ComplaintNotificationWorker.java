package com.ecofood.app.complaint;

import com.ecofood.app.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reuses Complaint's leased outbox and UC-016 deduplication. No delivery happens inside business writes. */
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name = {"ecofood.notifications.events-enabled", "ecofood.complaints.notifications-enabled"}, havingValue = "true", matchIfMissing = true)
public class ComplaintNotificationWorker {
  private final ComplaintOutboxRepository outbox;
  private final ComplaintNotificationSource source;
  private final NotificationService notifications;

  @Scheduled(fixedDelayString = "${ecofood.complaints.notification-poll-ms:5000}", initialDelayString = "${ecofood.complaints.notification-initial-delay-ms:15000}")
  public void poll() {
    for (int i = 0; i < 25; i++) {
      ComplaintCoreContracts.OutboxLease lease;
      try {
        var claimed = outbox.claimNext("complaint-notifications-v1");
        if (claimed.isEmpty()) return;
        lease = claimed.get();
      } catch (RuntimeException ex) { log.warn("Complaint notification polling deferred"); return; }
      try {
        var notice = source.forEvent(lease.event());
        if (notice.isPresent()) notifications.create(notice.get().notification());
        // Guest/INTERNAL have no Customer channel. Handlers use the scoped Complaint Inbox.
        outbox.acknowledge(lease);
      } catch (RuntimeException ex) {
        try { outbox.retry(lease); } catch (RuntimeException retryFailure) { /* Lease expiry makes this retryable. */ }
        log.warn("Complaint notification deferred; outbox retained for retry");
      }
    }
  }
}
