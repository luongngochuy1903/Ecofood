package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.*;
import com.ecofood.core.enums.*;
import java.time.Instant;

/** A Proposition has no review status of its own — only its effective dates gate it. */
public final class PlanAvailability {
    private PlanAvailability() {}

    public static boolean propositionActive(PropositionEntity p, Instant now) {
        return !Boolean.TRUE.equals(p.getDeleted())
                && within(p.getDefinition().startDate(), p.getDefinition().endDate(), now);
    }

    public static boolean active(PropositionEntity p, Plan plan, Instant now) {
        if (!propositionActive(p, now)
                || Boolean.TRUE.equals(plan.getDeleted())
                || plan.getStatus() != PlanStatusEnum.APPROVED
                || !within(plan.getStartDate(), plan.getEndDate(), now)) return false;
        return p.getPlans().stream()
                .noneMatch(
                        next ->
                                !Boolean.TRUE.equals(next.getDeleted())
                                        && next.getStatus() == PlanStatusEnum.APPROVED
                                        && plan.getIdAsString().equals(next.getPreviousVersionId())
                                        && (next.getStartDate() == null
                                                || !now.isBefore(next.getStartDate())));
    }

    private static boolean within(Instant start, Instant end, Instant now) {
        return (start == null || !now.isBefore(start)) && (end == null || now.isBefore(end));
    }
}
