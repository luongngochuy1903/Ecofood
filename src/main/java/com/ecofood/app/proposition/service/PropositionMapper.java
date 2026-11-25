package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.*;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.client.CoreUserQueryClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.PlanStatusEnum;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class PropositionMapper {
    private final PropositionAccess access;
    private final PropositionParties parties;
    private final PropositionCurrencies currencies;
    private final CoreProductProcessClient productProcesses;
    private final CoreUserQueryClient userQueryClient;

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
                d,
                plans,
                p.getActualMetrics() == null ? List.of() : p.getActualMetrics().stream()
                    .filter(row -> owner || actor.platform()
                        || row.tenantId() != null && actor.scope().contains(row.tenantId()))
                    .toList(),
                visible,
                currencies.supportedCurrencies(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                owner,
                owner,
                PlanAvailability.propositionActive(p, Instant.now()));
    }

    public PlanResponse plan(PropositionEntity p, Plan plan, PropositionAccess.Actor actor) {
        boolean manages = access.manages(actor, p, plan.getTenantIds());
        boolean edit =
                (plan.getStatus() == PlanStatusEnum.DRAFT
                                || plan.getStatus() == PlanStatusEnum.REJECTED)
                        && manages;
        boolean canSubmit = plan.getStatus() == PlanStatusEnum.DRAFT && manages;
        // Legacy data: a Plan whose own CAS content document never got
        // created (or was removed) shouldn't take the whole surrounding
        // Proposition — or worse, an entire directory/list page of them —
        // down with it. Degrade to a Plan with empty definition/costing
        // instead of propagating the 404 CoreProductProcessClient throws.
        ProductProcessResponse content;
        try {
            content = productProcesses.get(plan.getIdAsString());
        } catch (ResponseStatusException ex) {
            content = null;
        }
        var definition =
                content == null
                                || content.definition() == null
                                || content.definition().processElementAttribute() == null
                                || content.definition().processElementAttribute().isEmpty()
                        ? null
                        : content.definition().processElementAttribute().get(0);
        var costing =
                content == null
                                || content.costing() == null
                                || content.costing().processElementAttribute() == null
                                || content.costing().processElementAttribute().isEmpty()
                        ? null
                        : content.costing().processElementAttribute().get(0);
        return new PlanResponse(
                plan.getIdAsString(),
                plan.getVersion(),
                plan.getProductVersion() == null ? 0 : plan.getProductVersion(),
                plan.getCode(),
                definition == null ? null : definition.productName(),
                definition == null ? null : definition.description(),
                definition == null ? null : definition.category(),
                definition == null ? null : definition.imagesURL(),
                definition == null ? null : definition.ingredients(),
                definition == null ? null : definition.recipe(),
                costing,
                plan.getRegionIds(),
                plan.getTenantIds(),
                plan.getStartDate(),
                plan.getEndDate(),
                plan.getStatus(),
                approverName(plan.getApprovedBy()),
                plan.getApprovedAt(),
                plan.getRejectionReason(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                edit,
                access.owns(actor, p) && plan.getStatus() == PlanStatusEnum.PENDING_REVIEW,
                plan.getPreviousVersionId(),
                canSubmit,
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

    /**
     * Resolves the approving user's id into "Lastname Firstname" — cross-module
     * lookup via CoreUserQueryClient, never userACL's own repository/service
     * directly (see this project's module-boundary pattern). Falls back to the
     * raw id if the user can no longer be found (e.g. deleted).
     */
    private String approverName(String approvedBy) {
        if (approvedBy == null || approvedBy.isBlank()) return approvedBy;
        try {
            var user = userQueryClient.getById(approvedBy);
            String last = user.getLastName() == null ? "" : user.getLastName().trim();
            String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
            String name = (last + " " + first).trim();
            return name.isEmpty() ? approvedBy : name;
        } catch (RuntimeException notFound) {
            return approvedBy;
        }
    }
}
