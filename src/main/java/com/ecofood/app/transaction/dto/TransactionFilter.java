package com.ecofood.app.transaction.dto;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.core.enums.TransactionStatus;
import java.time.Instant;
import org.bson.types.ObjectId;

public record TransactionFilter(String invoiceId, String invoiceNumber,
    Instant from, Instant to, TransactionStatus status) {
  public TransactionFilter {
    if (invoiceId != null && !ObjectId.isValid(invoiceId))
      throw new BadRequestException("Invalid invoice identifier");
    if (invoiceNumber != null) {
      invoiceNumber = invoiceNumber.trim();
      if (invoiceNumber.length() > 100)
        throw new BadRequestException("Invoice number search must not exceed 100 characters");
    }
    if (from != null && to != null && from.isAfter(to))
      throw new BadRequestException("from must not be after to");
  }
}
