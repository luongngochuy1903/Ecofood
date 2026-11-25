package com.ecofood.app.audit.entity;

import com.ecofood.core.enums.AuditAction;
import com.ecofood.core.enums.AuditOutcome;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("audit_events")
public record AuditLogEntity(@Id String id, String tenantId, String actorId, AuditAction action,
                             String controller, Instant timestamp, AuditOutcome outcome, String requestId) {}
