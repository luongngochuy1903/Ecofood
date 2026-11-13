package com.ecofood.app.receipt.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Reuse this ID on transport retries; a new ID explicitly issues another link. */
public record IssueReceiptRequest(@NotNull UUID requestId) {}
