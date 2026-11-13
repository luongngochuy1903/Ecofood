package com.ecofood.app.receipt.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** No raw token, URL, secret or invoice/customer snapshot is persisted. */
@Data
@Document("receipt_access")
public class ReceiptAccess {
  @Id private String id;
  private String tokenHash;
  private String invoiceId;
  private String tenantId;
  private String posId;
  private String distributionPartnerId;
  private String issuedBy;
  private Instant issuedAt;
  private Instant expiresAt;
  private Instant revokedAt;
}
