package com.ecofood.app.customer;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CustomerDtos {
  private CustomerDtos() {}
  public record Profile(String id, String username, String email, String firstName, String lastName,
                        String userCategory, String tenantId, long revision) {}
  public record UpdateProfile(@NotBlank @Size(max = 100) String firstName,
                              @NotBlank @Size(max = 100) String lastName, @NotNull @Min(0) Long revision) {}
  public record Item(String productCode, String productName,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount) {}
  /** Deliberately omits POS, submitter, financial snapshots and arbitrary POS payload. */
  public record OwnedInvoice(String id, String invoiceNumber, String type, String status, String paymentStatus,
      Instant transactionTime, Instant receivedAt, String currency,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
      String invoiceDocumentId, List<Item> items) {}
  public record Page<T>(List<T> content, int page, int size, boolean hasNext) {}
}
