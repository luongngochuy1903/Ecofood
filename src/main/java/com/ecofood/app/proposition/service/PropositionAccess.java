package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.*;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class PropositionAccess {
    private final CoreTenantQueryClient tenants;
    private final ACLService acl;

    public record Actor(
            PropositionTenantReference tenant,
            Set<String> scope,
            Set<String> readScope,
            boolean enterprise,
            boolean platform) {}

    public Actor current() {
        if (AuthenticationContext.getToken() == null)
            throw error(HttpStatus.UNAUTHORIZED, "Authentication required");
        String id;
        List<String> roles;
        try {
            id = AuthenticationContext.getTenantId().toHexString();
            roles = AuthenticationContext.getRoles();
        } catch (RuntimeException ex) {
            throw error(HttpStatus.UNAUTHORIZED, "Invalid session");
        }
        var tenant = tenants.propositionReference(id);
        if (tenant.deleted()) throw error(HttpStatus.FORBIDDEN, "Tenant is unavailable");
        boolean enterprise =
                tenant.partnerType() == PartnerTypeEnum.ENTERPRISE
                        || tenant.partnerType() == PartnerTypeEnum.REGIONAL_ENTERPRISE;
        boolean platform = tenant.partnerType() == PartnerTypeEnum.ECO;
        String prefix =
                enterprise
                        ? "ENTERPRISE"
                        : platform
                                ? "ECO"
                                : tenant.tenantType() == TenantTypeEnum.MASTER_TENANT
                                        ? "MASTER"
                                        : "TENANT";
        if (roles == null
                || roles.stream()
                        .noneMatch(
                                role ->
                                        (prefix + "_ADMIN").equals(role)
                                                || (prefix + "_PRODUCT_MANAGER").equals(role)))
            throw error(HttpStatus.FORBIDDEN, "Product Library role required");
        if (enterprise
                && (!acl.hasEnterpriseLevelRole()
                        || tenant.enterpriseId() == null
                        || !ObjectId.isValid(tenant.enterpriseId())))
            throw error(HttpStatus.FORBIDDEN, "Enterprise association required");
        var scope =
                new HashSet<>(
                        tenant.scopedTenantIds() == null ? List.of() : tenant.scopedTenantIds());
        scope.add(id);
        var read = new HashSet<>(scope);
        if (tenant.relationships() != null)
            tenant.relationships().stream()
                    .filter(
                            r ->
                                    RelationshipTypeEnum.MASTER_TENANT
                                            .getCode()
                                            .equals(r.getRelationshipTypeCode()))
                    .map(PartyRelationshipResponse::getToPartyId)
                    .filter(Objects::nonNull)
                    .forEach(read::add);
        return new Actor(tenant, Set.copyOf(scope), Set.copyOf(read), enterprise, platform);
    }

    public Actor enterprise() {
        var actor = current();
        if (!actor.enterprise())
            throw error(HttpStatus.FORBIDDEN, "Only Enterprise may manage Propositions");
        return actor;
    }

    public boolean owns(Actor a, PropositionEntity p) {
        return a.enterprise()
                && p.getEnterpriseId().toHexString().equals(a.tenant().enterpriseId());
    }

    public boolean canRead(Actor a, PropositionEntity p) {
        if (Boolean.TRUE.equals(p.getDeleted())) return false;
        return owns(a, p)
                || a.platform()
                || (p.getDefinition().brandId() != null
                        && a.readScope().contains(p.getDefinition().brandId()))
                || p.getDefinition().distributionPartnerIds().stream()
                        .anyMatch(a.readScope()::contains)
                || p.getPlans().stream()
                        .filter(plan -> !Boolean.TRUE.equals(plan.getDeleted()))
                        .anyMatch(plan -> !Collections.disjoint(plan.getTenantIds(), a.scope()));
    }

    public void read(Actor a, PropositionEntity p) {
        if (!canRead(a, p)) throw error(HttpStatus.NOT_FOUND, "Proposition not found");
    }

    public void owner(Actor a, PropositionEntity p) {
        read(a, p);
        if (!owns(a, p))
            throw error(
                    HttpStatus.FORBIDDEN, "Only the owning Enterprise may edit this Proposition");
    }

    public boolean manages(Actor a, PropositionEntity p, List<String> ids) {
        return owns(a, p);
    }

    public void plan(Actor a, PropositionEntity p, List<String> ids) {
        read(a, p);
        if (!manages(a, p, ids))
            throw error(HttpStatus.FORBIDDEN, "Plan tenant scope is not authorized");
    }

    public static ResponseStatusException error(HttpStatus status, String text) {
        return new ResponseStatusException(status, text);
    }
}
