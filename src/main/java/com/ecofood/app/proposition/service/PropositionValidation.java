package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PropositionValidation {
    private final Validator validator;

    public void bean(Object body) {
        if (body == null) bad("Request is required");
        var errors = validator.validate(body);
        if (!errors.isEmpty())
            bad(
                    errors.stream()
                            .map(e -> e.getPropertyPath() + ": " + e.getMessage())
                            .sorted()
                            .findFirst()
                            .orElse("Invalid request"));
    }

    public ObjectId id(String value) {
        if (value == null || !ObjectId.isValid(value)) bad("A valid identifier is required");
        return new ObjectId(value);
    }

    public <T> List<T> list(List<T> value) {
        if (value == null) return List.of();
        if (value.stream().anyMatch(Objects::isNull)) bad("List entries cannot be null");
        return List.copyOf(value);
    }

    public List<String> codes(List<String> value) {
        var result = list(value).stream().map(String::trim).toList();
        if (result.stream().anyMatch(String::isBlank)
                || new HashSet<>(result).size() != result.size())
            bad("Scope entries must be non-blank and unique");
        return result;
    }

    public List<String> ids(List<String> value) {
        var result = codes(value).stream().map(v -> id(v).toHexString()).toList();
        if (new HashSet<>(result).size() != result.size()) bad("Identifiers must be unique");
        return result;
    }

    public void revision(PropositionEntity p, Long expected) {
        if (expected == null || !expected.equals(p.getRevision()))
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Proposition changed; reload before retrying");
    }

    public void dates(Instant start, Instant end) {
        if (start != null && end != null && !start.isBefore(end))
            bad("End date must be after start date");
    }

    public void metrics(PropositionDefinition d) {
        var shares =
                Arrays.asList(
                        d.enterpriseProfitShare(),
                        d.platformProfitShare(),
                        d.distributionProfitShare());
        if (shares.stream().anyMatch(Objects::nonNull)
                && (shares.stream().anyMatch(Objects::isNull)
                        || shares.stream()
                                        .filter(Objects::nonNull)
                                        .reduce(
                                                java.math.BigDecimal.ZERO,
                                                java.math.BigDecimal::add)
                                        .compareTo(new java.math.BigDecimal("100"))
                                != 0))
            bad(
                    "Platform, Enterprise and Partner profit shares must be provided together and total 100%");
        periods(list(d.expectMetrics()));
        var seen = new HashSet<String>();
        for (var metric : list(d.partnerMetrics())) {
            if (!seen.add(metric.tenantId())) bad("Partner indicators must be unique");
            if (!d.distributionPartnerIds().contains(metric.tenantId()))
                bad("Indicators must reference a selected distribution partner");
            periods(list(metric.expectMetrics()));
        }
    }

    private void periods(List<EconomicMetricsRequest> value) {
        var periods = new HashSet<String>();
        for (var metric : value) {
            var period = metric.quarter() == null ? "M" + metric.month() : "Q" + metric.quarter();
            if (!periods.add(metric.year() + "-" + period))
                bad("Indicators must have unique periods within each year");
        }
    }

    public BigDecimal revenue(List<EconomicMetricsRequest> metrics, BigDecimal fallback) {
        var amounts =
                list(metrics).stream()
                        .map(EconomicMetricsRequest::revenue)
                        .filter(Objects::nonNull)
                        .toList();
        return amounts.isEmpty()
                ? fallback
                : amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * `content` is the Plan's own cloned product-process (see
     * CoreProductProcessClient) — a Plan always carries one (created
     * alongside the Plan itself), so a missing/null value here would mean
     * the Core client call already threw rather than this method being
     * reached. There's no more "permitted customization" to gate: a Plan
     * is a full, always-editable clone, not a sparse set of overrides.
     *
     * <p>Deliberately does not require the Plan's own bound Product version
     * to be APPROVED — a Plan can be submitted for review and approved
     * independently of that Product version's own review status.
     */
    public void plan(
            PropositionEntity p,
            Plan plan,
            List<PropositionTenantReference> eligible,
            boolean approving,
            ProductProcessResponse content) {
        var d = p.getDefinition();
        if (plan.getTenantIds() == null || plan.getTenantIds().isEmpty())
            bad("Plan distribution Sub-Tenants are required");
        if (approving) {
            bean(d);
            metrics(d);
        }
        if (plan.getRegionIds() == null || plan.getRegionIds().size() != 1)
            bad("Select exactly one Country of Sale for each Plan");
        if (!d.regionIds().containsAll(plan.getRegionIds()))
            bad("Plan regions must be permitted by the Proposition");
        var allowed = eligible.stream().map(PropositionTenantReference::id).toList();
        if (!allowed.containsAll(plan.getTenantIds()))
            bad("Plan tenants must be selected distribution Sub-Tenants");
        for (var tenant : eligible)
            if (plan.getTenantIds().contains(tenant.id())
                    && !plan.getRegionIds().contains(tenant.country()))
                bad(
                        "Plan Country of Sale must match every selected Sub-Tenant's onboarding country");
        if (approving
                && (content == null
                        || content.costing() == null
                        || content.costing().processElementAttribute() == null
                        || content.costing().processElementAttribute().isEmpty()
                        || content.costing().processElementAttribute().get(0).sellingPrice()
                                == null))
            bad("A selling price is required before approval");
    }

    public static void bad(String text) {
        throw PropositionAccess.error(HttpStatus.BAD_REQUEST, text);
    }
}
