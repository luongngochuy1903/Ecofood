package com.ecofood.app.customer;

import com.ecofood.app.notification.Notification;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;

/** Processing view; financial Invoice fields are inherited and never rewritten by claim. */
@Getter
@Setter
@Document("invoice")
public class CustomerInvoice extends Invoice {
  private Receipt customerReceipt;
  private Ownership customerOwnership;
  private Handoff customerClaimHandoff;
  public record Receipt(String id, String requestId, String tokenHash, String issuedBy, Instant issuedAt, Instant expiresAt, Instant revokedAt) {}
  public record Ownership(String userId, Instant claimedAt, String method, String receiptId) {}
  public record Handoff(Submission event, Notification notification, boolean pending, Instant nextRetryAt, int attempts) {}
}
