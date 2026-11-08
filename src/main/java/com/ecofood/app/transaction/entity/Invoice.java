package com.ecofood.app.transaction.entity;

import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Data
@Document("invoice")
public class Invoice {
  @Id private String id;
  private String tenantId;
  private String posId;
  private String submittedBy;
  private String distributionPartnerId;
  private String invoiceNumber;
  private String planId;
  private String propositionId;
  private Instant transactionTime;
  private Instant receivedAt;
  private TransactionStatus status;
  private String currency;
  @Field(targetType = FieldType.DECIMAL128) private BigDecimal totalAmount;
  private List<InvoiceItemDto> items;
}
