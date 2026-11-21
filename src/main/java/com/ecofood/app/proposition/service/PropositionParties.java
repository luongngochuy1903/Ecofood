package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

@Component
@RequiredArgsConstructor
public class PropositionParties {
    private final CoreTenantQueryClient tenants;

    /** Exactly the self + direct MT-child scope used by Tenant Directory. */
    public List<PropositionTenantReference> linked(
            PropositionTenantReference owner, PartnerTypeEnum type) {
        return directoryPartners(owner).stream().filter(t -> t.partnerType() == type).toList();
    }

    public List<PropositionTenantReference> directoryPartners(PropositionTenantReference owner) {
        var scope = new HashSet<>(tenants.getScopedTenantIds(owner.id()));
        return tenants.propositionReferences().stream()
                .filter(
                        t ->
                                !t.deleted()
                                        && scope.contains(t.id())
                                        && t.tenantType() == TenantTypeEnum.SUB_TENANT
                                        && (t.partnerType() == PartnerTypeEnum.BRAND
                                                || t.partnerType()
                                                        == PartnerTypeEnum.ECOSYSTEM_PARTNER))
                .sorted(
                        Comparator.comparing(
                                PropositionTenantReference::name,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    public List<PropositionTenantReference> selected(
            PropositionTenantReference owner, List<String> ids) {
        var allowed = directoryPartners(owner);
        if (ids == null
                || ids.isEmpty()
                || !allowed.stream().map(PropositionTenantReference::id).toList().containsAll(ids))
            throw PropositionAccess.error(
                    HttpStatus.BAD_REQUEST,
                    "Distribution partners must be BRAND or ECOSYSTEM_PARTNER Sub-Tenants in the Enterprise Tenant Directory");
        return allowed.stream().filter(t -> ids.contains(t.id())).toList();
    }

    public List<String> countries(PropositionTenantReference owner, List<String> ids) {
        var selected = selected(owner, ids);
        for (var t : selected)
            if (t.country() == null || t.country().isBlank())
                throw PropositionAccess.error(
                        HttpStatus.BAD_REQUEST,
                        "Set the onboarding contact country for distribution partner: " + t.name());
        return selected.stream()
                .map(PropositionTenantReference::country)
                .distinct()
                .sorted()
                .toList();
    }

    public void validate(PropositionEntity p) {
        var owner = tenants.propositionReference(p.getOwningTenantId().toHexString());
        if (owner.deleted() || !p.getEnterpriseId().toHexString().equals(owner.enterpriseId()))
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Enterprise association is unavailable");
        var brand = p.getDefinition().brandId();
        if (brand != null
                && linked(owner, PartnerTypeEnum.BRAND).stream()
                        .noneMatch(t -> t.id().equals(brand)))
            throw PropositionAccess.error(
                    HttpStatus.BAD_REQUEST, "Brand must be in this Enterprise Tenant Directory");
        if (!new HashSet<>(countries(owner, p.getDefinition().distributionPartnerIds()))
                .equals(new HashSet<>(p.getDefinition().regionIds())))
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT,
                    "Partner countries have changed; update the Proposition before managing Plans");
    }

    public List<PropositionTenantReference> eligible(PropositionEntity p) {
        var owner = tenants.propositionReference(p.getOwningTenantId().toHexString());
        return directoryPartners(owner).stream()
                .filter(t -> p.getDefinition().distributionPartnerIds().contains(t.id()))
                .toList();
    }
}
