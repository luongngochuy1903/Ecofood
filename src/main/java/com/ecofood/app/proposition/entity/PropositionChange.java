package com.ecofood.app.proposition.entity;

import java.time.Instant;
import com.ecofood.core.dto.*;

/** Embedded with its aggregate: a failed optimistic save publishes neither data nor audit. */
public record PropositionChange(
        String action,
        String actorId,
        Instant at,
        String planId,
        PropositionDefinition definition,
        PlanRequest plan,
        String status,
        Integer version,
        String previousVersionId) {}
