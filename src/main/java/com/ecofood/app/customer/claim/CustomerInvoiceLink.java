package com.ecofood.app.customer.claim;

import com.ecofood.core.entity.BaseEntity;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Getter
@Setter
@Document("customer_invoice_link")
public class CustomerInvoiceLink extends BaseEntity {
  @com.fasterxml.jackson.annotation.JsonIgnore
  private java.util.List<com.ecofood.app.notification.NotificationOutboxEvent> notificationOutbox;
  @Field("customer_identifier") private String customerId;
  @Field("invoice_identifier") private String invoiceId;
  /** Original seller Tenant; never replaced by the CustomerPortal Tenant. */
  @Field("tenant_identifier") private String tenantId;
  @Field("distribution_partner_identifier") private String distributionPartnerId;
  @Field("customer_tenant_identifier") private String customerTenantId;
  @Field("claimed_at") private Instant claimedAt;
  @Field("claim_status") private ClaimStatus claimStatus;
  @Field("receipt_access_identifier") private String receiptAccessId;
  @Field("claim_proof_identifier") private String claimProofId;

  public enum ClaimStatus { CLAIMED }
}
