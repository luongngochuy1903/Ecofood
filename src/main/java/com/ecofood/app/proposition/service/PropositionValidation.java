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

    public void dates(Instant start, Instant end, Instant parentStart, Instant parentEnd) {
        if (start != null && end != null && !start.isBefore(end))
            bad("End date must be after start date");
        if (parentStart != null && (start == null || start.isBefore(parentStart)))
            bad("Plan start must be within the Proposition period");
        if (parentEnd != null && (end == null || end.isAfter(parentEnd)))
            bad("Plan end must be within the Proposition period");
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

    public void proposition(PropositionEntity p) {
        var d = p.getDefinition();
        bean(d);
        metrics(d);
        dates(d.startDate(), d.endDate(), null, null);
        if (d.targetYear() == null
                || d.expectRevenue() == null
                || d.enterpriseProfitShare() == null
                || d.platformProfitShare() == null
                || d.distributionProfitShare() == null)
            bad(
                    "Complete Proposition target year, expected revenue and profit shares before submitting for review");
        if (p.getSourceProcess() == null
                || p.getSourceProcess().status() != ProductStatusEnum.APPROVED)
            bad("An APPROVED ProductProcess is required");
    }

    public void plan(
            PropositionEntity p,
            Plan plan,
            List<PropositionTenantReference> eligible,
            boolean approving) {
        var d = p.getDefinition();
        if (plan.getName() == null
                || plan.getName().isBlank()
                || plan.getTenantIds() == null
                || plan.getTenantIds().isEmpty())
            bad("Plan name and distribution Sub-Tenants are required");
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
        dates(plan.getStartDate(), plan.getEndDate(), d.startDate(), d.endDate());
        var o = plan.getProductOverrides();
        if (o != null) {
            if (o.recipe() != null) {
                if (!d.allowedOverrides().contains(PlanOverrideField.RECIPE))
                    bad("Product Process recipe customization is not permitted");
                bean(o.recipe());
                if (o.recipe().enabled()
                        && (o.recipe().steps() == null || o.recipe().steps().isEmpty()))
                    bad("Enabled Product Process recipe requires steps");
                if (o.recipe().steps() != null) {
                    var orders = new HashSet<Integer>();
                    for (var step : o.recipe().steps())
                        if (step == null || !orders.add(step.order()))
                            bad(
                                    "Product Process steps must be non-null and have unique order values");
                }
            }
            if (o.name() != null
                    && (!d.allowedOverrides().contains(PlanOverrideField.NAME)
                            || o.name().isBlank()))
                bad("Product name override is not permitted or is blank");
            if (o.description() != null
                    && !d.allowedOverrides().contains(PlanOverrideField.DESCRIPTION))
                bad("Description override is not permitted");
            if (o.imageUrl() != null) {
                if (!d.allowedOverrides().contains(PlanOverrideField.IMAGE_URL))
                    bad("Image override is not permitted");
                if (!o.imageUrl().isBlank())
                    try {
                        var uri = java.net.URI.create(o.imageUrl());
                        if (!("https".equalsIgnoreCase(uri.getScheme())
                                        || "http".equalsIgnoreCase(uri.getScheme()))
                                || uri.getHost() == null) bad("Image must be an HTTP(S) URL");
                    } catch (IllegalArgumentException ex) {
                        bad("Image must be an HTTP(S) URL");
                    }
            }
        }
        if (approving && plan.getMarketConfiguration() == null)
            bad("Market configuration is required before approval");
        if (p.getSourceProcess() == null
                || p.getSourceProcess().status() != ProductStatusEnum.APPROVED)
            bad("An APPROVED ProductProcess is required");
        for (var other : p.getPlans())
            if (!Boolean.TRUE.equals(other.getDeleted())
                    && !other.getId().equals(plan.getId())
                    && !(plan.getPreviousVersionId() != null
                            && plan.getPreviousVersionId().equals(other.getIdAsString()))
                    && !(other.getPreviousVersionId() != null
                            && other.getPreviousVersionId().equals(plan.getIdAsString()))
                    && overlaps(plan, other))
                bad("Plan overlaps another Plan in country, tenant and effective period");
    }

    private boolean overlaps(Plan a, Plan b) {
        if (Collections.disjoint(a.getRegionIds(), b.getRegionIds())
                || Collections.disjoint(a.getTenantIds(), b.getTenantIds())) return false;
        return (a.getEndDate() == null
                        || b.getStartDate() == null
                        || b.getStartDate().isBefore(a.getEndDate()))
                && (b.getEndDate() == null
                        || a.getStartDate() == null
                        || a.getStartDate().isBefore(b.getEndDate()));
    }

    public static void bad(String text) {
        throw PropositionAccess.error(HttpStatus.BAD_REQUEST, text);
    }
}
