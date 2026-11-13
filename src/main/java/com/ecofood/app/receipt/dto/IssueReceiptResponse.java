package com.ecofood.app.receipt.dto;

import java.time.Instant;

/** receiptUrl can be encoded directly as QR content in the next frontend task. */
public record IssueReceiptResponse(String receiptId, String invoiceId, String receiptUrl, Instant expiresAt) {}
