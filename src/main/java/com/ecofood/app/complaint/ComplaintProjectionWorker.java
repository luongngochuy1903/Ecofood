package com.ecofood.app.complaint;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "ecofood.complaints.projection-enabled", havingValue = "true", matchIfMissing = true)
public class ComplaintProjectionWorker {
  private final ComplaintProjectionService projection;
  @Scheduled(fixedDelayString = "${ecofood.complaints.projection-poll-ms:5000}", initialDelayString = "${ecofood.complaints.projection-initial-delay-ms:15000}")
  public void poll() { projection.recover(); }
}
