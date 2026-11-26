package com.ecofood.app.customer.entity;

import com.ecofood.app.userACL.entity.UserEntity;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** Customer specialization of the same app_user identity, never a separate Customer collection. */
@Getter
@Setter
@org.springframework.data.annotation.TypeAlias("com.ecofood.app.customer.CustomerUser")
@Document("app_user")
public class CustomerUser extends UserEntity {
  private Long customerProfileRevision;
  private String customerIdentityIssuer;
  private java.time.Instant customerEmailVerifiedAt;
  private String customerVerifiedEmailHash;

  /**
   * Distribution-partner-generated ids linked to this Customer via the
   * external-customer QR flow (ExternalCustomerServiceImpl.link) — a
   * submit-transaction whose Sale Orchestrator has createCustomer enabled
   * matches its invoiceHolder's externalId against this list to auto-link
   * the new Invoice.
   */
  @Field("external_ids")
  private List<String> externalIds = new ArrayList<>();
}
