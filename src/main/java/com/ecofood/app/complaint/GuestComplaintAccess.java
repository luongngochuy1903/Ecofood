package com.ecofood.app.complaint;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("complaint_guest_access")
public record GuestComplaintAccess(@Id String complaintId, String receiptAccessId, String tokenHash, Instant issuedAt, Instant expiresAt, Instant revokedAt) {}
