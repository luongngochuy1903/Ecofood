package com.ecofood.app.userACL.service;

import com.ecofood.core.client.CoreEnterpriseQueryClient;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.PropositionTenantReference;
import com.ecofood.core.enums.PartnerTypeEnum;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Relationships for dashboards and their DAR configuration; does not alter the Tenant directory or login scope. */
@Service
@RequiredArgsConstructor
public class DashboardTenantHierarchy {
  private final CoreTenantQueryClient tenants;
  private final CoreEnterpriseQueryClient enterprises;

  public List<String> directRegionalChildren(String tenantId) {
    var tree = load(tenants.propositionReference(tenantId));
    return tree.children().getOrDefault(tenantId, Set.of()).stream()
        .filter(id -> tree.tenants().get(id).partnerType() == PartnerTypeEnum.REGIONAL_ENTERPRISE).toList();
  }

  public List<String> directParents(PropositionTenantReference tenant) {
    var tree = load(tenant);
    return tree.children().entrySet().stream().filter(entry -> entry.getValue().contains(tenant.id()))
        .map(Map.Entry::getKey).toList();
  }

  public record Tree(Map<String, PropositionTenantReference> tenants, Map<String, Set<String>> children) {
    public Set<String> descendants(String root) {
      Set<String> result = new LinkedHashSet<>();
      Deque<String> pending = new ArrayDeque<>(); pending.add(root);
      while (!pending.isEmpty()) {
        String id = pending.removeFirst();
        if (result.add(id)) pending.addAll(children.getOrDefault(id, Set.of()));
      }
      return result;
    }
  }

  public Tree load(PropositionTenantReference root) {
    Map<String, PropositionTenantReference> refs = new LinkedHashMap<>();
    refs.put(root.id(), root);
    for (var ref : tenants.propositionReferences()) if (!ref.deleted()) refs.put(ref.id(), ref);
    Map<String, Set<String>> children = new LinkedHashMap<>();
    for (var parent : refs.values()) {
      if (!enterprise(parent)) continue;
      Set<String> owned = parent.enterpriseId() == null ? Set.of()
          : new HashSet<>(enterprises.dashboardChildEnterpriseIds(parent.enterpriseId()));
      for (var child : refs.values()) {
        if (parent.id().equals(child.id())) continue;
        boolean mt = child.relationships() != null && child.relationships().stream().anyMatch(r ->
            "MT".equals(r.getRelationshipTypeCode()) && parent.id().equals(r.getToPartyId()));
        boolean own = child.partnerType() == PartnerTypeEnum.REGIONAL_ENTERPRISE
            && child.enterpriseId() != null && owned.contains(child.enterpriseId());
        if (mt || own) children.computeIfAbsent(parent.id(), ignored -> new LinkedHashSet<>()).add(child.id());
      }
    }
    return new Tree(refs, children);
  }

  private boolean enterprise(PropositionTenantReference tenant) {
    return tenant.partnerType() == PartnerTypeEnum.ENTERPRISE || tenant.partnerType() == PartnerTypeEnum.REGIONAL_ENTERPRISE;
  }
}
