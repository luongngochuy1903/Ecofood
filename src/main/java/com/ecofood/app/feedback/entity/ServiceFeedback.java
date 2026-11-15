package com.ecofood.app.feedback.entity;

import com.ecofood.core.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Getter
@Setter
@Document("service_feedback")
public class ServiceFeedback extends BaseEntity {
  @Field("invoice_identifier") private String invoiceId;
  @Field("tenant_identifier") private String tenantId;
  @Field("distribution_partner_identifier") private String distributionPartnerId;
  private int rating;
  private String comment;
  private ServiceFeedbackStatus status;
}
