package com.ecofood.app.customer.dto;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.core.enums.TransactionStatus;
import java.time.Instant;

public record CustomerPurchaseFilter(String invoiceCode, Instant from, Instant to,
    String distributionPartnerId, TransactionStatus status) {
  public CustomerPurchaseFilter {
    invoiceCode = invoiceCode == null ? null : invoiceCode.trim();
    distributionPartnerId = distributionPartnerId == null ? null : distributionPartnerId.trim();
    if ((invoiceCode != null && invoiceCode.length() > 100)
        || (distributionPartnerId != null && distributionPartnerId.length() > 100))
      throw new BadRequestException("Search values must not exceed 100 characters");
    if (from != null && to != null && from.isAfter(to)) throw new BadRequestException("from must not be after to");
  }
}
