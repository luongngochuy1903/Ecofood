package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.core.client.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.PropositionService;
import com.ecofood.core.service.SequenceGeneratorService;
import com.ecofood.core.util.FormatCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PropositionServiceImpl implements PropositionService {
    private final PropositionRepository repository;
    private final CoreProductQueryClient products;
    private final CoreEnterpriseQueryClient enterprises;
    private final CoreProductProcessClient productProcesses;
    private final PropositionAccess access;
    private final PropositionValidation validation;
    private final PropositionParties parties;
    private final PropositionCurrencies currencies;
    private final PropositionMapper mapper;
    private final SequenceGeneratorService sequenceGeneratorService;

    @Override
    public PropositionResponse create(PropositionCreateRequest r) {
        var actor = access.enterprise();
        validation.bean(r);
        // The Proposition itself no longer pins any Product version or
        // ProductProcess — it only needs the Product to exist and belong to
        // the actor's Enterprise. Each Plan independently picks and is
        // bound to its own approved version when added (see newPlan), so a
        // future change to one Product version only affects the Plans
        // bound to it, never the Proposition as a whole.
        var product = products.getById(r.productId());
        if (!actor.tenant().enterpriseId().equals(product.enterpriseId()))
            throw PropositionAccess.error(HttpStatus.NOT_FOUND, "Product not found");
        var p =
                PropositionEntity.builder()
                        .enterpriseId(validation.id(product.enterpriseId()))
                        .owningTenantId(validation.id(actor.tenant().id()))
                        .productId(validation.id(product.id()))
                        .enterpriseName(
                                enterprises
                                        .getById(product.enterpriseId())
                                        .getEnterpriseCompanyName())
                        .build();
        p.setId(new ObjectId());
        p.setDefinition(definition(p, r.definition()));
        // Zeroed so the Actual Metrics tab shows real 0-valued rows (not an
        // empty table) from the moment a Proposition exists — the same
        // quarters the user just defined in expectMetrics, never written to
        // again here; new quarters added later (edits, or a sale landing
        // outside any of these) are created on demand by recordActualSale.
        p.setActualMetrics(zeroedActualMetrics(p.getDefinition().expectMetrics()));
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
        p.setDefinition(definition(p, r.definition()));
        parties.validate(p);
        var eligible = parties.eligible(p);
        for (var plan : p.getPlans())
            if (!Boolean.TRUE.equals(plan.getDeleted()))
                validation.plan(
                        p,
                        plan,
                        eligible,
                        plan.getStatus() != PlanStatusEnum.DRAFT,
                        productProcesses.get(plan.getIdAsString()));
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
        var plan = newPlan(p, r);
        validation.plan(
                p,
                plan,
                parties.eligible(p),
                false,
                productProcesses.get(plan.getIdAsString()));
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
        var next = updatedPlan(old, r);
        validation.plan(
                p,
                next,
                parties.eligible(p),
                false,
                productProcesses.get(next.getIdAsString()));
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
        var content = productProcesses.get(plan.getIdAsString());
        validation.plan(p, plan, parties.eligible(p), true, content);
        currencies.resolvePlan(p.getEnterpriseId(), planCurrency(p, content));
        plan.setStatus(PlanStatusEnum.APPROVED);
        plan.setApprovedAt(Instant.now());
        plan.setApprovedBy(AuthenticationContext.getUserId().toHexString());
        stamp(plan);
        change(p, plan, "APPROVE_PLAN", null);
        save(p);
        return mapper.response(p, actor);
    }

    @Override
    public PropositionResponse rejectPlan(String id, String planId, PlanDecisionRequest r) {
        var actor = access.current();
        var p = load(id);
        access.read(actor, p);
        var plan = findPlan(p, planId);
        access.plan(actor, p, plan.getTenantIds());
        validation.bean(r);
        validation.revision(p, r.expectedRevision());
        if (plan.getStatus() != PlanStatusEnum.PENDING_REVIEW)
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Only Pending Review Plans can be rejected");
        plan.setStatus(PlanStatusEnum.REJECTED);
        plan.setRejectionReason(r.reason().trim());
        stamp(plan);
        change(p, plan, "REJECT_PLAN", null);
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
    public List<PendingPlanApprovalResponse> pendingPlanApprovals(String productId, int version) {
        if (productId == null || !ObjectId.isValid(productId)) return List.of();
        var results = new ArrayList<PendingPlanApprovalResponse>();
        for (var p : repository.findByProductIdAndDeletedFalse(new ObjectId(productId))) {
            for (var plan : p.getPlans()) {
                if (Boolean.TRUE.equals(plan.getDeleted())) continue;
                if (!Objects.equals(plan.getProductVersion(), version)) continue;
                if (plan.getStatus() != PlanStatusEnum.PENDING_REVIEW) continue;
                results.add(
                        new PendingPlanApprovalResponse(
                                p.getIdAsString(),
                                p.getDefinition() == null ? null : p.getDefinition().name(),
                                plan.getIdAsString(),
                                plan.getCode(),
                                planName(plan)));
            }
        }
        return results;
    }

    /**
     * System cascade, called only from the already-authorized Product-approval
     * flow (see ProductServiceImpl.approve): a Product version that was
     * APPROVED and just got demoted back to Pending Review takes any of its
     * own APPROVED Plans — across every Proposition sourced from this
     * Product — back to Pending Review with it. Skips the usual
     * parties/currency re-validation a human-driven approvePlan runs, same
     * simplification ProductVersionReviewService's own cascade already makes
     * — this only ever flips a status that was already valid once.
     */
    @Override
    public void demoteApprovedPlans(String productId, int version) {
        if (productId == null || !ObjectId.isValid(productId)) return;
        for (var p : repository.findByProductIdAndDeletedFalse(new ObjectId(productId))) {
            boolean changed = false;
            for (var plan : p.getPlans()) {
                if (Boolean.TRUE.equals(plan.getDeleted())) continue;
                if (!Objects.equals(plan.getProductVersion(), version)) continue;
                if (plan.getStatus() != PlanStatusEnum.APPROVED) continue;
                plan.setStatus(PlanStatusEnum.PENDING_REVIEW);
                plan.setApprovedAt(null);
                plan.setApprovedBy(null);
                stamp(plan);
                changeAs(p, plan, "PRODUCT_VERSION_DEMOTED_PLAN", null, "PRODUCT_VERSION_REVIEW");
                changed = true;
            }
            if (changed) save(p);
        }
    }

    /**
     * System cascade, called only from the already-authorized Product-approval
     * flow — approves exactly the given Plans (each must still be Pending
     * Review and bound to this Product version); any other id is silently
     * ignored rather than failing the whole batch.
     */
    @Override
    public void approvePlans(String productId, int version, List<String> planIds) {
        if (productId == null || !ObjectId.isValid(productId) || planIds == null || planIds.isEmpty()) return;
        var wanted = new HashSet<>(planIds);
        var actor = AuthenticationContext.getUserId().toHexString();
        for (var p : repository.findByProductIdAndDeletedFalse(new ObjectId(productId))) {
            boolean changed = false;
            for (var plan : p.getPlans()) {
                if (!wanted.contains(plan.getIdAsString())) continue;
                if (Boolean.TRUE.equals(plan.getDeleted())) continue;
                if (!Objects.equals(plan.getProductVersion(), version)) continue;
                if (plan.getStatus() != PlanStatusEnum.PENDING_REVIEW) continue;
                plan.setStatus(PlanStatusEnum.APPROVED);
                plan.setApprovedAt(Instant.now());
                plan.setApprovedBy(actor);
                stamp(plan);
                changeAs(p, plan, "APPROVE_PLAN", null, actor);
                changed = true;
            }
            if (changed) save(p);
        }
    }

    private static final java.time.ZoneId ACTUAL_METRICS_ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    @Override
    public void recordActualSale(String propositionId, Instant occurredAt, BigDecimal totalSalesVolume,
            BigDecimal ingredientCost, BigDecimal platformCost, BigDecimal profit) {
        if (propositionId == null || !ObjectId.isValid(propositionId)) return;
        var date = occurredAt.atZone(ACTUAL_METRICS_ZONE);
        int year = date.getYear();
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        for (int attempt = 0; attempt < 5; attempt++) {
            var p = repository.findById(new ObjectId(propositionId))
                    .filter(v -> !Boolean.TRUE.equals(v.getDeleted()))
                    .orElse(null);
            if (p == null) return;
            p.setActualMetrics(
                    accumulateActualMetrics(
                            p.getActualMetrics(), year, quarter, totalSalesVolume, ingredientCost,
                            platformCost, profit));
            try {
                repository.save(p);
                return;
            } catch (OptimisticLockingFailureException retry) {
                // Reload and retry — this is a denormalized reporting figure,
                // not the source of truth (the already-committed Invoice is),
                // so it's worth a few attempts but never worth failing the
                // submit-transaction response over.
            }
        }
    }

    private List<EconomicMetricsRequest> zeroedActualMetrics(List<EconomicMetricsRequest> expectMetrics) {
        if (expectMetrics == null) return List.of();
        return expectMetrics.stream()
                .map(
                        m ->
                                new EconomicMetricsRequest(
                                        null,
                                        m.year(),
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO,
                                        quarterOf(m),
                                        BigDecimal.ZERO))
                .toList();
    }

    private Integer quarterOf(EconomicMetricsRequest m) {
        if (m.quarter() != null) return m.quarter();
        return m.month() == null ? null : (m.month() - 1) / 3 + 1;
    }

    /** `revenue` and `totalSalesVolume` are both set to the same sold amount — no finer distinction exists at submit time. */
    private List<EconomicMetricsRequest> accumulateActualMetrics(
            List<EconomicMetricsRequest> existing,
            int year,
            int quarter,
            BigDecimal totalSalesVolume,
            BigDecimal ingredientCost,
            BigDecimal platformCost,
            BigDecimal profit) {
        var list = existing == null ? new ArrayList<EconomicMetricsRequest>() : new ArrayList<>(existing);
        for (int i = 0; i < list.size(); i++) {
            var m = list.get(i);
            if (Objects.equals(m.year(), year) && Objects.equals(m.quarter(), quarter)) {
                list.set(
                        i,
                        new EconomicMetricsRequest(
                                null,
                                year,
                                addAmount(m.totalSalesVolume(), totalSalesVolume),
                                addAmount(m.profit(), profit),
                                addAmount(m.ingredientCost(), ingredientCost),
                                addAmount(m.platformCost(), platformCost),
                                quarter,
                                addAmount(m.revenue(), totalSalesVolume)));
                return List.copyOf(list);
            }
        }
        list.add(
                new EconomicMetricsRequest(
                        null, year, totalSalesVolume, profit, ingredientCost, platformCost, quarter,
                        totalSalesVolume));
        return List.copyOf(list);
    }

    private BigDecimal addAmount(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a).add(b == null ? BigDecimal.ZERO : b);
    }

    /**
     * The Plan's own name lives on its cloned product process, not on the
     * embedded Plan itself — legacy data whose content was never created
     * (or was removed) shouldn't take this whole response down with it.
     */
    private String planName(Plan plan) {
        try {
            var content = productProcesses.get(plan.getIdAsString());
            var d = definitionOf(content);
            return d == null ? null : d.productName();
        } catch (ResponseStatusException ex) {
            return null;
        }
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
        var content = productProcesses.get(plan.getIdAsString());
        validation.plan(p, plan, parties.eligible(p), true, content);
        currencies.resolvePlan(p.getEnterpriseId(), planCurrency(p, content));
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
        var oldContent = productProcesses.get(old.getIdAsString());
        var next =
                Plan.builder()
                        .version(old.getVersion() + 1)
                        .productVersion(old.getProductVersion())
                        .code(old.getCode())
                        .regionIds(old.getRegionIds())
                        .tenantIds(old.getTenantIds())
                        .startDate(old.getStartDate())
                        .endDate(old.getEndDate())
                        .status(PlanStatusEnum.DRAFT)
                        .previousVersionId(old.getIdAsString())
                        .build();
        next.setId(new ObjectId());
        next.setCreatedAt(Instant.now());
        next.setCreatedBy(AuthenticationContext.getUserId());
        stamp(next);
        productProcesses.save(next.getIdAsString(), familyRequest(oldContent));
        var plans = new ArrayList<>(p.getPlans());
        plans.add(next);
        p.setPlans(List.copyOf(plans));
        change(p, next, "CREATE_PLAN_VERSION", null);
        save(p);
        return mapper.response(p, actor);
    }

    private ProductFamilySaveRequest familyRequest(ProductProcessResponse content) {
        var d = definitionOf(content);
        var c = costingOf(content);
        return new ProductFamilySaveRequest(
                d == null ? null : d.productName(),
                d == null ? null : d.description(),
                d == null ? null : d.category(),
                d == null ? null : d.imagesURL(),
                d == null ? null : d.ingredients(),
                d == null ? null : d.recipe(),
                c == null
                        ? null
                        : new ProductCostingRequest(
                                c.ingredientCost(),
                                c.packagingCost(),
                                c.laborCost(),
                                c.operationCost(),
                                c.marketingFee(),
                                c.targetMargin(),
                                c.tax(),
                                c.currency()),
                c == null || c.sellingPrice() == null
                        ? null
                        : new ProductPricingRequest(c.sellingPrice(), c.totalCost(), c.currency(), c.note()));
    }

    private static ProductDefinitionAttributeResponse definitionOf(ProductProcessResponse content) {
        return content.definition() == null
                        || content.definition().processElementAttribute() == null
                        || content.definition().processElementAttribute().isEmpty()
                ? null
                : content.definition().processElementAttribute().get(0);
    }

    private static ProductCostingAttributeResponse costingOf(ProductProcessResponse content) {
        return content.costing() == null
                        || content.costing().processElementAttribute() == null
                        || content.costing().processElementAttribute().isEmpty()
                ? null
                : content.costing().processElementAttribute().get(0);
    }

    /** The Enterprise currency policy keys off the Plan's own costing currency now, not a separate market-config currency. */
    private String planCurrency(PropositionEntity p, ProductProcessResponse content) {
        var c = costingOf(content);
        return c == null || c.currency() == null ? p.getDefinition().currency() : c.currency();
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

    private Plan newPlan(PropositionEntity p, PlanRequest r) {
        // Server-generated, same pattern as ProductEntity's own code (see
        // ProductServiceImpl.create) — never user-provided.
        long sequence = sequenceGeneratorService.generateSequence(Plan.SEQUENCE_NAME);
        var plan =
                Plan.builder()
                        .version(1)
                        .productVersion(r.productVersion())
                        .code(FormatCode.byPaddingZeros(sequence, 5, "PL"))
                        .regionIds(validation.codes(r.regionIds()))
                        .tenantIds(validation.ids(r.tenantIds()))
                        .startDate(
                                r.startDate() == null
                                        ? p.getDefinition().startDate()
                                        : r.startDate())
                        .endDate(r.endDate() == null ? p.getDefinition().endDate() : r.endDate())
                        .status(PlanStatusEnum.DRAFT)
                        .build();
        plan.setId(new ObjectId());
        plan.setCreatedAt(Instant.now());
        plan.setCreatedBy(AuthenticationContext.getUserId());
        stamp(plan);
        saveFamily(plan, r);
        return plan;
    }

    private Plan updatedPlan(Plan old, PlanRequest r) {
        boolean wasRejected = old.getStatus() == PlanStatusEnum.REJECTED;
        var next =
                Plan.builder()
                        .version(old.getVersion())
                        .productVersion(r.productVersion())
                        .code(old.getCode())
                        .regionIds(validation.codes(r.regionIds()))
                        .tenantIds(validation.ids(r.tenantIds()))
                        .startDate(r.startDate() == null ? old.getStartDate() : r.startDate())
                        .endDate(r.endDate() == null ? old.getEndDate() : r.endDate())
                        .status(wasRejected ? PlanStatusEnum.DRAFT : old.getStatus())
                        .rejectionReason(wasRejected ? null : old.getRejectionReason())
                        .previousVersionId(old.getPreviousVersionId())
                        .build();
        next.setId(old.getId());
        next.setCreatedAt(old.getCreatedAt());
        next.setCreatedBy(old.getCreatedBy());
        stamp(next);
        saveFamily(next, r);
        return next;
    }

    private void saveFamily(Plan plan, PlanRequest r) {
        productProcesses.save(
                plan.getIdAsString(),
                new ProductFamilySaveRequest(
                        r.name().trim(),
                        r.description(),
                        blank(r.category()),
                        r.imageUrl(),
                        r.ingredients(),
                        r.recipe(),
                        r.costing(),
                        r.pricing()));
    }

    private void stamp(Plan p) {
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(AuthenticationContext.getUserId());
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void draft(Plan p) {
        if (p.getStatus() != PlanStatusEnum.DRAFT && p.getStatus() != PlanStatusEnum.REJECTED)
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Only Draft or Rejected Plans may be changed");
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
                        plan == null ? null : plan.getStatus().name(),
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
