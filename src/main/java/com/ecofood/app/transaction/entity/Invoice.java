package com.ecofood.app.transaction.entity;

import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.app.submission.entity.Submission;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.*;

@Data
@Document("invoice")
public class Invoice {
  @Id private String id;
  private String tenantId;
  private String submittedBy;
  private String posId;
  private String distributionPartnerId;
  private String propositionId;
  private String planId;
  private String orchestratorId;
  private int orchestratorVersion;
  private String invoiceNumber;
  private String currency;
  private Instant transactionTime;
  private Instant receivedAt;
  private TransactionStatus status;
  private String type;
  private String paymentStatus;
  private String submissionId;
  private String invoiceDocumentId;
  private InvoiceHolder invoiceHolder;
  private InvoiceSnapshot snapshot;

  public record InvoiceHolder(String customerId, String userId) {}

  @JsonIgnore private Submission submissionHandoff;
  @JsonIgnore private boolean submissionPending;
  @JsonIgnore private Instant submissionRetryAt;
  @JsonIgnore private int submissionAttempts;

  @Field(targetType = FieldType.DECIMAL128)
  private BigDecimal totalAmount;

  private List<InvoiceItemDto> items;
  private Map<String, Object> orchestratorPayload;

  /** Durable handoff for downstream workers; no email or Customer work runs before insert. */
  private Map<String, String> postWorkflowResults;
}
