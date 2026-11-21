package com.ecofood.app.customer.entity;

import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;

/** Processing view; financial Invoice fields are inherited and never rewritten by claim. */
@Getter
@Setter
@org.springframework.data.annotation.TypeAlias("com.ecofood.app.customer.CustomerInvoice")
@Document("invoice")
public class CustomerInvoice extends Invoice {
  private Receipt customerReceipt;
  private Ownership customerOwnership;
  private Handoff customerClaimHandoff;
  @org.springframework.data.annotation.TypeAlias("com.ecofood.app.customer.CustomerInvoice$Receipt")
  public record Receipt(String id, String requestId, String tokenHash, String issuedBy, Instant issuedAt, Instant expiresAt, Instant revokedAt) {}
  @org.springframework.data.annotation.TypeAlias("com.ecofood.app.customer.CustomerInvoice$Ownership")
  public record Ownership(String userId, Instant claimedAt, String method, String receiptId) {}
  @org.springframework.data.annotation.TypeAlias("com.ecofood.app.customer.CustomerInvoice$Handoff")
  public record Handoff(Submission event, Notification notification, boolean pending, Instant nextRetryAt, int attempts) {}
}
