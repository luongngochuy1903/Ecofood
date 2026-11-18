package com.ecofood.app.customer.claim;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Hash-only, short-lived capability. Replays can only return the same immutable ownership. */
@Data
@Document("invoice_claim_proof")
public class InvoiceClaimProof {
  @Id private String id;
  private String customerId;
  private String customerTenantId;
  private String invoiceId;
  private String tenantId;
  private String distributionPartnerId;
  private String receiptAccessId;
  private String receiptTokenHash;
  private Instant createdAt;
  private Instant expiresAt;
}
