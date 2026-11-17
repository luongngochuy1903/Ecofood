package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.PlanStatusEnum;
import com.ecofood.core.enums.PropositionStatusEnum;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PropositionMapper {
    private final PropositionAccess access;
    private final PropositionParties parties;
    private final PropositionCurrencies currencies;

    public PropositionResponse response(PropositionEntity p, PropositionAccess.Actor actor) {
        boolean owner = access.owns(actor, p);
        var eligible = parties.eligible(p);
        var visible =
                owner
                        ? eligible
                        : eligible.stream().filter(t -> actor.scope().contains(t.id())).toList();
        var plans =
                p.getPlans().stream()
                        .filter(plan -> !Boolean.TRUE.equals(plan.getDeleted()))
                        .filter(
                                plan ->
                                        owner
                                                || actor.platform()
                                                || !Collections.disjoint(
                                                        plan.getTenantIds(), actor.scope()))
                        .map(plan -> plan(p, plan, actor))
                        .toList();
        var d = p.getDefinition();
        if (!owner && !actor.platform())
            d =
                    new PropositionDefinition(
                            d.name(),
                            d.description(),
                            d.regionIds(),
                            d.brandId(),
                            d.distributionPartnerIds(),
                            d.currency(),
                            d.startDate(),
                            d.endDate(),
                            d.expectRevenue(),
                            d.expectMetrics(),
                            d.partnerMetrics().stream()
                                    .filter(m -> actor.readScope().contains(m.tenantId()))
                                    .toList(),
                            d.enterpriseProfitShare(),
                            d.platformProfitShare(),
                            d.distributionProfitShare(),
                            d.allowedOverrides(),
                            d.targetYear(),
                            d.currentRevenue());
        return new PropositionResponse(
                p.getIdAsString(),
                p.getRevision(),
                p.getEnterpriseId().toHexString(),
                p.getEnterpriseName(),
                p.getProductId().toHexString(),
                p.getProductVersion(),
                p.getProductProcessId().toHexString(),
                p.getSourceProcess(),
                d,
                p.getStatus().name(),
                plans,
                p.getActualMetrics(),
                visible,
                currencies.settings(p.getEnterpriseId()).availableCurrencies(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                owner && p.getStatus() == PropositionStatusEnum.DRAFT,
                owner,
                owner && p.getStatus() == PropositionStatusEnum.DRAFT,
                owner && p.getStatus() == PropositionStatusEnum.PENDING_REVIEW,
                p.getApprovedBy(),
                p.getApprovedAt(),
                PlanAvailability.propositionActive(p, Instant.now()));
    }

    public PlanResponse plan(PropositionEntity p, Plan plan, PropositionAccess.Actor actor) {
        boolean edit =
                plan.getStatus() == PlanStatusEnum.DRAFT
                        && access.manages(actor, p, plan.getTenantIds());
        return new PlanResponse(
                plan.getIdAsString(),
                plan.getVersion(),
                plan.getName(),
                plan.getCode(),
                plan.getDescription(),
                plan.getRegionIds(),
                plan.getTenantIds(),
                plan.getProductOverrides(),
                plan.getMarketConfiguration(),
                plan.getStartDate(),
                plan.getEndDate(),
                plan.getStatus(),
                plan.getApprovedBy(),
                plan.getApprovedAt(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                edit,
                access.owns(actor, p) && plan.getStatus() == PlanStatusEnum.PENDING_REVIEW,
                plan.getPreviousVersionId(),
                edit,
                PlanAvailability.active(p, plan, Instant.now()),
                access.owns(actor, p)
                        && plan.getStatus() == PlanStatusEnum.APPROVED
                        && p.getPlans().stream()
                                .noneMatch(
                                        next ->
                                                !Boolean.TRUE.equals(next.getDeleted())
                                                        && plan.getIdAsString()
                                                                .equals(
                                                                        next
                                                                                .getPreviousVersionId())));
    }
}
