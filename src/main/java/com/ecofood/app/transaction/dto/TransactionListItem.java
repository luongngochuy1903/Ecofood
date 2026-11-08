package com.ecofood.app.transaction.dto;

import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionListItem(String id, String invoiceNumber, String tenantId,
    String posId, String distributionPartnerId, Instant transactionTime,
    Instant receivedAt, TransactionStatus status, String currency, BigDecimal totalAmount) {
  public static TransactionListItem from(Invoice invoice) {
    return new TransactionListItem(invoice.getId(), invoice.getInvoiceNumber(), invoice.getTenantId(),
        invoice.getPosId(), invoice.getDistributionPartnerId(), invoice.getTransactionTime(),
        invoice.getReceivedAt(), invoice.getStatus(), invoice.getCurrency(), invoice.getTotalAmount());
  }
}
