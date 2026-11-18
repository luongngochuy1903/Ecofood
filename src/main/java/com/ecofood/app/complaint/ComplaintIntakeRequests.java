package com.ecofood.app.complaint;

import com.ecofood.app.config.BadRequestException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public final class ComplaintIntakeRequests {
  private ComplaintIntakeRequests() {}
  public interface Strict { @JsonAnySetter default void reject(String field, Object ignored) { throw new BadRequestException("Unsupported Complaint request field"); } }
  public record InvoiceIssue(@NotBlank @Size(max=128) String requestId, @NotBlank @Size(max=64) String categoryCode,
      @NotBlank @Size(max=200) String subject, @NotBlank @Size(max=10000) String description, @Size(max=32) String invoiceItemId) implements Strict {}
  public record GuestInvoice(@NotBlank @Size(max=43) String receiptToken, @NotBlank @Size(max=128) String requestId, @NotBlank @Size(max=64) String categoryCode,
      @NotBlank @Size(max=200) String subject, @NotBlank @Size(max=10000) String description, @Size(max=32) String invoiceItemId) implements Strict {
    public InvoiceIssue issue() { return new InvoiceIssue(requestId, categoryCode, subject, description, invoiceItemId); }
  }
  public record CustomerInvoice(@NotBlank @Size(max=24) String invoiceId, @NotBlank @Size(max=128) String requestId, @NotBlank @Size(max=64) String categoryCode,
      @NotBlank @Size(max=200) String subject, @NotBlank @Size(max=10000) String description, @Size(max=32) String invoiceItemId) implements Strict {
    public InvoiceIssue issue() { return new InvoiceIssue(requestId, categoryCode, subject, description, invoiceItemId); }
  }
  public record TechnicalContext(@Size(max=100) String page, @Size(max=80) String feature, @Size(max=64) String errorCode) implements Strict {}
  public record PlatformIssue(@NotBlank @Size(max=128) String requestId, @NotBlank @Size(max=64) String categoryCode,
      @NotBlank @Size(max=200) String subject, @NotBlank @Size(max=9000) String description,
      @jakarta.validation.Valid TechnicalContext technicalContext) implements Strict {}
  public record InternalIssue(@NotBlank @Size(max=128) String requestId, @NotBlank @Size(max=64) String categoryCode,
      @NotBlank @Size(max=200) String subject, @NotBlank @Size(max=10000) String description) implements Strict {}
  public record ReceiptProof(@NotBlank @Size(max=43) String receiptToken) implements Strict {}
  public record Track(@NotBlank @Size(max=24) String complaintId, @NotBlank @Size(max=43) String accessSecret) implements Strict {}
}
