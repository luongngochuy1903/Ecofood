package com.ecofood.app.customer.entity;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Tracks one issued "scan to identify yourself" QR for a distribution
 * partner's externalId — issued BEFORE any Invoice/transaction exists, so it
 * cannot live on Invoice the way CustomerInvoice.Receipt does. Once a
 * Customer scans and signs in, {@code linkedCustomerId} is set and the same
 * externalId is appended to that Customer's own externalIds list
 * (see ExternalCustomerServiceImpl.link).
 */
@Getter
@Setter
@Document("external_customer_link")
public class ExternalCustomerLink {
  @Id private String id;
  private String tenantId;
  private String issuedBy;
  private String externalId;
  private String tokenHash;
  private Instant issuedAt;
  private Instant expiresAt;
  private Instant revokedAt;
  private String linkedCustomerId;
  private Instant linkedAt;
}
