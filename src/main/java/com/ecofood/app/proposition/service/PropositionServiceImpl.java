package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.core.client.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.PropositionService;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PropositionServiceImpl implements PropositionService {
    private final PropositionRepository repository;
    private final CoreProductQueryClient products;
    private final CoreEnterpriseQueryClient enterprises;
    private final PropositionAccess access;
    private final PropositionValidation validation;
    private final PropositionParties parties;
    private final PropositionCurrencies currencies;
    private final MarketConfigurationCalculator calculator;
    private final PropositionMapper mapper;

    @Override
    public PropositionResponse create(PropositionCreateRequest r) {
        var actor = access.enterprise();
        validation.bean(r);
        var product = products.getApprovedVersion(r.productId(), r.productVersion());
        if (!actor.tenant().enterpriseId().equals(product.enterpriseId()))
            throw PropositionAccess.error(HttpStatus.NOT_FOUND, "Product not found");
        var source =
                products.processes(product.id()).stream()
                        .filter(
                                p ->
                                        p.version() == r.productVersion()
                                                && p.id().equals(r.productProcessId())
                                                && p.status() == ProductStatusEnum.APPROVED)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        PropositionAccess.error(
                                                HttpStatus.BAD_REQUEST,
                                                "Exactly one APPROVED ProductProcess is required"));
        var p =
                PropositionEntity.builder()
                        .enterpriseId(validation.id(product.enterpriseId()))
                        .owningTenantId(validation.id(actor.tenant().id()))
                        .productId(validation.id(product.id()))
                        .productVersion(source.version())
                        .productProcessId(validation.id(source.id()))
                        .sourceProcess(source)
                        .enterpriseName(
                                enterprises
                                        .getById(product.enterpriseId())
                                        .getEnterpriseCompanyName())
                        .build();
        p.setId(new ObjectId());
        p.setDefinition(definition(p, r.definition()));
        parties.validate(p);
        change(p, null, "CREATE", null);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionResponse update(String id, PropositionUpdateRequest r) {
        var actor = access.enterprise();
        var p = load(id);
        access.owner(actor, p);
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        propositionDraft(p);
        p.setDefinition(definition(p, r.definition()));
        parties.validate(p);
        var eligible = parties.eligible(p);
        for (var plan : p.getPlans())
            if (!Boolean.TRUE.equals(plan.getDeleted()))
                validation.plan(p, plan, eligible, plan.getStatus() != PlanStatusEnum.DRAFT);
        change(p, null, "UPDATE", null);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionResponse addPlan(String id, PlanRequest r) {
        var actor = access.current();
        var p = load(id);
        access.read(actor, p);
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        access.plan(actor, p, r.tenantIds());
        parties.validate(p);
        var plan = plan(p, r);
        validation.plan(p, plan, parties.eligible(p), false);
        var plans = new ArrayList<>(p.getPlans());
        plans.add(plan);
        p.setPlans(List.copyOf(plans));
        change(p, plan, "ADD_PLAN", r);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionResponse updatePlan(String id, String planId, PlanRequest r) {
        var actor = access.current();
        var p = load(id);
        access.read(actor, p);
        var old = findPlan(p, planId);
        access.plan(actor, p, old.getTenantIds());
        draft(old);
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        access.plan(actor, p, r.tenantIds());
        parties.validate(p);
        var next = plan(p, r);
        next.setId(old.getId());
        next.setVersion(old.getVersion());
        next.setCreatedAt(old.getCreatedAt());
        next.setCreatedBy(old.getCreatedBy());
        next.setPreviousVersionId(old.getPreviousVersionId());
        validation.plan(p, next, parties.eligible(p), false);
        p.setPlans(
                p.getPlans().stream()
                        .map(plan -> plan.getId().equals(old.getId()) ? next : plan)
                        .toList());
        change(p, next, "UPDATE_PLAN", r);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionResponse removePlan(String id, String planId, RevisionRequest r) {
        var actor = access.current();
        var p = load(id);
        access.read(actor, p);
        var plan = findPlan(p, planId);
        access.plan(actor, p, plan.getTenantIds());
        draft(plan);
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        plan.setDeleted(true);
        stamp(plan);
        change(p, plan, "REMOVE_PLAN", null);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionResponse approvePlan(String id, String planId, RevisionRequest r) {
        var actor = access.current();
        var p = load(id);
        access.read(actor, p);
        var plan = findPlan(p, planId);
        access.plan(actor, p, plan.getTenantIds());
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        if (plan.getStatus() != PlanStatusEnum.PENDING_REVIEW)
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Only Pending Review Plans can be approved");
        parties.validate(p);
        validation.plan(p, plan, parties.eligible(p), true);
        currencies.resolvePlan(p.getEnterpriseId(), plan.getMarketConfiguration().currency());
        plan.setStatus(PlanStatusEnum.APPROVED);
        plan.setApprovedAt(Instant.now());
        plan.setApprovedBy(AuthenticationContext.getUserId().toHexString());
        stamp(plan);
        change(p, plan, "APPROVE_PLAN", null);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionCurrencySettingsResponse updateCurrencySettings(
            PropositionCurrencySettingsRequest r) {
        var actor = access.enterprise();
        validation.bean(r);
        return currencies.update(validation.id(actor.tenant().enterpriseId()), r);
    }

    @Override
    public PropositionResponse submit(String id, RevisionRequest r) {
        var actor = access.enterprise();
        var p = load(id);
        access.owner(actor, p);
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        propositionDraft(p);
        reviewProposition(p);
        p.setStatus(PropositionStatusEnum.PENDING_REVIEW);
        change(p, null, "SUBMIT_PROPOSITION", null);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionResponse approve(String id, RevisionRequest r) {
        var actor = access.enterprise();
        var p = load(id);
        access.owner(actor, p);
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        if (p.getStatus() != PropositionStatusEnum.PENDING_REVIEW)
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Only Pending Review Propositions can be approved");
        reviewProposition(p);
        p.setStatus(PropositionStatusEnum.APPROVED);
        p.setApprovedAt(Instant.now());
        p.setApprovedBy(AuthenticationContext.getUserId().toHexString());
        change(p, null, "APPROVE_PROPOSITION", null);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionResponse submitPlan(String id, String planId, RevisionRequest r) {
        var actor = access.enterprise();
        var p = load(id);
        access.owner(actor, p);
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        var plan = findPlan(p, planId);
        draft(plan);
        parties.validate(p);
        validation.plan(p, plan, parties.eligible(p), true);
        currencies.resolvePlan(p.getEnterpriseId(), plan.getMarketConfiguration().currency());
        plan.setStatus(PlanStatusEnum.PENDING_REVIEW);
        stamp(plan);
        change(p, plan, "SUBMIT_PLAN", null);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionResponse versionPlan(String id, String planId, RevisionRequest r) {
        var actor = access.enterprise();
        var p = load(id);
        access.owner(actor, p);
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        var old = findPlan(p, planId);
        parties.validate(p);
        if (old.getStatus() != PlanStatusEnum.APPROVED)
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Only Approved Plans can have a new Draft version");
        if (p.getPlans().stream()
                .anyMatch(
                        plan ->
                                !Boolean.TRUE.equals(plan.getDeleted())
                                        && old.getIdAsString().equals(plan.getPreviousVersionId())))
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "A successor version already exists; open that version");
        var market = old.getMarketConfiguration();
        var body =
                new PlanRequest(
                        p.getRevision(),
                        old.getName(),
                        old.getCode(),
                        old.getDescription(),
                        old.getRegionIds(),
                        old.getTenantIds(),
                        old.getProductOverrides(),
                        market == null
                                ? null
                                : new MarketConfigurationRequest(
                                        market.currency(),
                                        market.salePrice(),
                                        market.taxRate(),
                                        market.surcharge()),
                        old.getStartDate(),
                        old.getEndDate());
        var next = plan(p, body);
        next.setVersion(old.getVersion() + 1);
        next.setPreviousVersionId(old.getIdAsString());
        var plans = new ArrayList<>(p.getPlans());
        plans.add(next);
        p.setPlans(List.copyOf(plans));
        change(p, next, "CREATE_PLAN_VERSION", body);
        save(p);
        return mapper.response(p, actor);
    }

    private PropositionDefinition definition(PropositionEntity p, PropositionDefinition d) {
        var owner = access.enterprise().tenant();
        var partnerIds = validation.ids(d.distributionPartnerIds());
        var countries = parties.countries(owner, partnerIds);
        var result =
                new PropositionDefinition(
                        d.name().trim(),
                        d.description(),
                        countries,
                        blank(d.brandId()) == null
                                ? null
                                : validation.id(d.brandId()).toHexString(),
                        partnerIds,
                        currencies.resolve(p.getEnterpriseId(),
                                blank(d.currency()) == null && p.getDefinition() != null
                                        ? p.getDefinition().currency() : d.currency()),
                        d.startDate(),
                        d.endDate(),
                        validation.revenue(d.expectMetrics(), d.expectRevenue()),
                        validation.list(d.expectMetrics()),
                        validation.list(d.partnerMetrics()).stream()
                                .map(
                                        metric ->
                                                new PartnerMetricsRequest(
                                                        metric.tenantId(),
                                                        validation.revenue(
                                                                metric.expectMetrics(),
                                                                metric.expectRevenue()),
                                                        validation.list(metric.expectMetrics())))
                                .toList(),
                        d.enterpriseProfitShare(),
                        d.platformProfitShare(),
                        d.distributionProfitShare(),
                        d.allowedOverrides() == null ? Set.of() : Set.copyOf(d.allowedOverrides()),
                        d.targetYear(),
                        d.currentRevenue());
        validation.dates(result.startDate(), result.endDate(), null, null);
        validation.metrics(result);
        return result;
    }

    private Plan plan(PropositionEntity p, PlanRequest r) {
        var plan =
                Plan.builder()
                        .version(1)
                        .name(r.name().trim())
                        .code(blank(r.code()))
                        .description(r.description())
                        .regionIds(validation.codes(r.regionIds()))
                        .tenantIds(validation.ids(r.tenantIds()))
                        .productOverrides(r.productOverrides())
                        .startDate(
                                r.startDate() == null
                                        ? p.getDefinition().startDate()
                                        : r.startDate())
                        .endDate(r.endDate() == null ? p.getDefinition().endDate() : r.endDate())
                        .status(PlanStatusEnum.DRAFT)
                        .build();
        plan.setId(new ObjectId());
        plan.setCreatedAt(Instant.now());
        plan.setCreatedBy(AuthenticationContext.getUserId().toHexString());
        stamp(plan);
        if (r.marketConfiguration() != null)
            plan.setMarketConfiguration(
                    calculator.calculate(
                            r.marketConfiguration(),
                            currencies.resolvePlan(
                                    p.getEnterpriseId(), r.marketConfiguration().currency())));
        return plan;
    }

    private void stamp(Plan p) {
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(AuthenticationContext.getUserId().toHexString());
    }

    private void reviewProposition(PropositionEntity p) {
        parties.validate(p);
        validation.proposition(p);
        var eligible = parties.eligible(p);
        for (var plan : p.getPlans())
            if (!Boolean.TRUE.equals(plan.getDeleted())
                    && plan.getStatus() == PlanStatusEnum.APPROVED)
                validation.plan(p, plan, eligible, true);
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void propositionDraft(PropositionEntity p) {
        if (p.getStatus() != PropositionStatusEnum.DRAFT)
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Only Draft Propositions may be changed");
    }

    private void draft(Plan p) {
        if (p.getStatus() != PlanStatusEnum.DRAFT)
            throw PropositionAccess.error(HttpStatus.CONFLICT, "Only Draft Plans may be changed");
    }

    private Plan findPlan(PropositionEntity p, String id) {
        return p.getPlans().stream()
                .filter(
                        plan ->
                                !Boolean.TRUE.equals(plan.getDeleted())
                                        && plan.getIdAsString().equals(id))
                .findFirst()
                .orElseThrow(() -> PropositionAccess.error(HttpStatus.NOT_FOUND, "Plan not found"));
    }

    private PropositionEntity load(String id) {
        if (id == null || !ObjectId.isValid(id))
            throw PropositionAccess.error(HttpStatus.NOT_FOUND, "Proposition not found");
        return repository
                .findById(new ObjectId(id))
                .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                .orElseThrow(
                        () ->
                                PropositionAccess.error(
                                        HttpStatus.NOT_FOUND, "Proposition not found"));
    }

    private void change(PropositionEntity p, Plan plan, String action, PlanRequest body) {
        changeAs(p, plan, action, body, AuthenticationContext.getUserId().toHexString());
    }

    private void changeAs(
            PropositionEntity p, Plan plan, String action, PlanRequest body, String actor) {
        var changes = new ArrayList<>(p.getChanges());
        changes.add(
                new PropositionChange(
                        action,
                        actor,
                        Instant.now(),
                        plan == null ? null : plan.getIdAsString(),
                        plan == null ? p.getDefinition() : null,
                        body,
                        plan == null ? p.getStatus().name() : plan.getStatus().name(),
                        plan == null ? null : plan.getVersion(),
                        plan == null ? null : plan.getPreviousVersionId()));
        p.setChanges(List.copyOf(changes));
    }

    private void save(PropositionEntity p) {
        try {
            repository.save(p);
        } catch (OptimisticLockingFailureException ex) {
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Proposition changed; reload before retrying");
        }
    }
}
