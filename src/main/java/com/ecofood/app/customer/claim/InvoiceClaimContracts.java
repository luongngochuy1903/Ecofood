package com.ecofood.app.customer.claim;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.receipt.dto.PublicReceiptResponse;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class InvoiceClaimContracts {
  private InvoiceClaimContracts() {}
  public enum Status { ELIGIBLE, CLAIMED_BY_YOU, CLAIMED_BY_ANOTHER_CUSTOMER }

  public record ReceiptRequest(@NotBlank @Size(max = 128) String receiptToken) {
    @JsonAnySetter public void rejectUnknown(String name, Object ignored) { throw new BadRequestException("Unsupported claim field"); }
  }
  public record ClaimRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String claimProof,
      @NotNull @AssertTrue Boolean confirmed) {
    @JsonAnySetter public void rejectUnknown(String name, Object ignored) { throw new BadRequestException("Unsupported claim field"); }
  }
  public record Eligibility(Status status, PublicReceiptResponse invoice, String claimProof, Instant proofExpiresAt, Instant claimedAt) {}
  public record ClaimResult(Status status, String invoiceId, String linkId, Instant claimedAt) {}
  public record ClaimStatusResponse(Status status, Instant claimedAt) {}
}
