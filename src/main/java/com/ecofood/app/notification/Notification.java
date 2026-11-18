package com.ecofood.app.notification;

import com.ecofood.core.entity.BaseEntity;
import com.ecofood.app.notification.NotificationContracts.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Getter @Setter
@Document("notification")
public class Notification extends BaseEntity {
  @Field("recipient_customer_identifier") private String recipientCustomerId;
  @Field("customer_tenant_identifier") private String customerTenantId;
  @Field("source_tenant_identifier") private String sourceTenantId;
  @Field("distribution_partner_identifier") private String distributionPartnerId;
  private Type type;
  private String title;
  private String message;
  @Field("reference_type") private ReferenceType referenceType;
  @Field("reference_identifier") private String referenceId;
  @Field("action_target") private ActionTarget actionTarget;
  @Field("delivery_channel") private Channel deliveryChannel;
  @Field("delivery_status") private DeliveryStatus deliveryStatus;
  @Field("suppression_reason") private String suppressionReason;
  @Field("is_read") private boolean read;
  @Field("read_at") private Instant readAt;
  @Field("event_id") private String eventId;
  @Field("event_fingerprint") private String eventFingerprint;
  @Field("sent_at") private Instant sentAt;
  private int deliveryAttempts;
  private Instant nextDeliveryAttemptAt;
  private String deliveryLeaseId;
  private Instant deliveryLeaseUntil;
  private String lastDeliveryError;
  private String deliveryProvider;
  private String providerMessageId;
  private Instant deliveryCompletedAt;
}
