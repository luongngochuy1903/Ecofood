package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.core.client.CoreProductQueryClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.service.PropositionQueryService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PropositionQueryServiceImpl implements PropositionQueryService {
    private final PropositionRepository repository;
    private final PropositionAccess access;
    private final PropositionMapper mapper;
    private final PropositionParties parties;
    private final PropositionCurrencies currencies;
    private final CoreProductQueryClient products;

    @Override
    public Page<PropositionResponse> list(String search, Pageable page) {
        var actor = access.current();
        page(page);
        var scope = List.copyOf(actor.readScope());
        var candidates =
                actor.enterprise()
                        ? repository.findByEnterpriseIdAndDeletedFalse(
                                new ObjectId(actor.tenant().enterpriseId()))
                        : actor.platform()
                                ? repository.findByDeletedFalse()
                                : repository
                                        .findByDefinitionDistributionPartnerIdsInOrDefinitionBrandIdInOrPlansTenantIdsIn(
                                                scope, scope, scope);
        var needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        var result =
                candidates.stream()
                        .filter(p -> access.canRead(actor, p))
                        .filter(
                                p ->
                                        needle.isBlank()
                                                || p.getDefinition()
                                                        .name()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains(needle))
                        .sorted(
                                Comparator.comparing(
                                                PropositionEntity::getUpdatedAt,
                                                Comparator.nullsLast(Comparator.reverseOrder()))
                                        .thenComparing(PropositionEntity::getIdAsString))
                        .toList();
        int from = (int) Math.min(page.getOffset(), result.size()),
                to = Math.min(from + page.getPageSize(), result.size());
        return new PageImpl<>(
                result.subList(from, to).stream().map(p -> mapper.response(p, actor)).toList(),
                page,
                result.size());
    }

    @Override
    public PropositionResponse getById(String id) {
        var actor = access.current();
        var p = load(id);
        access.read(actor, p);
        return mapper.response(p, actor);
    }

    @Override
    public PlanResponse getPlan(String id, String planId) {
        var p = getById(id);
        return p.plans().stream()
                .filter(plan -> plan.id().equals(planId))
                .findFirst()
                .orElseThrow(() -> PropositionAccess.error(HttpStatus.NOT_FOUND, "Plan not found"));
    }

    @Override
    public PropositionOptionsResponse options() {
        var actor = access.current();
        if (!actor.enterprise())
            return new PropositionOptionsResponse(
                    false, List.of(), List.of(), "USD", List.of("USD"));
        var policy = currencies.settings(new ObjectId(actor.tenant().enterpriseId()));
        return new PropositionOptionsResponse(
                true,
                parties.linked(actor.tenant(), PartnerTypeEnum.BRAND),
                parties.directoryPartners(actor.tenant()),
                policy.defaultCurrency(),
                policy.availableCurrencies());
    }

    @Override
    public PropositionCurrencySettingsResponse currencySettings() {
        var actor = access.enterprise();
        return currencies.settings(new ObjectId(actor.tenant().enterpriseId()));
    }

    @Override
    public Page<ApprovedProductProcessReference> approvedProcesses(
            String search, Pageable pageable) {
        access.enterprise();
        page(pageable);
        var result = new ArrayList<ApprovedProductProcessReference>();
        int next = 0;
        Page<ProductResponse> batch;
        do {
            batch = products.list(search, null, null, PageRequest.of(next++, 100));
            for (var product : batch.getContent())
                for (var process : products.processes(product.id()))
                    if (process.status() == ProductStatusEnum.APPROVED)
                        result.add(
                                new ApprovedProductProcessReference(
                                        product.id(),
                                        process.version(),
                                        process.id(),
                                        process.definition()
                                                .processElementAttribute()
                                                .get(0)
                                                .productName(),
                                        process));
        } while (next < batch.getTotalPages());
        int from = (int) Math.min(pageable.getOffset(), result.size()),
                to = Math.min(from + pageable.getPageSize(), result.size());
        return new PageImpl<>(result.subList(from, to), pageable, result.size());
    }

    private void page(Pageable page) {
        if (page.isUnpaged() || page.getPageSize() > 100)
            throw PropositionAccess.error(
                    HttpStatus.BAD_REQUEST, "Page size must be between 1 and 100");
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
}
