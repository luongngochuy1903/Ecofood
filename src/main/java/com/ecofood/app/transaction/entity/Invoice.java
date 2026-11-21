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
  private String tenantId, submittedBy, posId, distributionPartnerId, propositionId, planId;
  private String orchestratorId;
  private int orchestratorVersion;
  private String invoiceNumber, currency;
  private Instant transactionTime, receivedAt;
  private TransactionStatus status;
  private String type, paymentStatus, submissionId, invoiceDocumentId;
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
