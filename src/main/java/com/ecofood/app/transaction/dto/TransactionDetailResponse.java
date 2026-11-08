package com.ecofood.app.transaction.dto;

import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TransactionDetailResponse(String id, String invoiceNumber, String tenantId,
    String posId, String submittedBy, String distributionPartnerId, String planId,
    String propositionId, Instant transactionTime, Instant receivedAt,
    TransactionStatus status, String currency, BigDecimal totalAmount, List<InvoiceItemDto> items) {
  public static TransactionDetailResponse from(Invoice invoice) {
    return new TransactionDetailResponse(invoice.getId(), invoice.getInvoiceNumber(), invoice.getTenantId(),
        invoice.getPosId(), invoice.getSubmittedBy(), invoice.getDistributionPartnerId(),
        invoice.getPlanId(), invoice.getPropositionId(), invoice.getTransactionTime(),
        invoice.getReceivedAt(), invoice.getStatus(), invoice.getCurrency(), invoice.getTotalAmount(),
        List.copyOf(invoice.getItems()));
  }
}
