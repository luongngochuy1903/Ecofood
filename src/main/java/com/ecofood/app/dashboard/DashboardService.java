package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardDtos.*;

import com.ecofood.app.metrics.entity.ActualMetrics;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {
  private final DashboardAccess access;
  private final DashboardData data;
  private final DashboardAggregation aggregation;
  private final CoreTenantQueryClient tenants;
  private record Context(DashboardAccess.Actor actor, List<PropositionEntity> propositions, Set<String> tenantIds, List<ActualMetrics> actual) {}
  static Set<String> participants(PropositionEntity p) {
    Set<String> ids = new HashSet<>(p.getDefinition().distributionPartnerIds());
    if (p.getPlans() != null) p.getPlans().stream().filter(plan -> !Boolean.TRUE.equals(plan.getDeleted()))
        .filter(plan -> plan.getTenantIds() != null).forEach(plan -> ids.addAll(plan.getTenantIds()));
    return Set.copyOf(ids);
  }
  private Context context(Selection selection) {
    var actor = access.current(); validate(selection);
    var props = data.propositions(actor);
    if (selection.propositionId() != null) {
      props = props.stream().filter(p -> p.getIdAsString().equals(selection.propositionId())).toList();
      if (props.isEmpty()) throw DashboardAccess.error(HttpStatus.NOT_FOUND, "Proposition not found in Dashboard scope");
    }
    Set<String> selected = new HashSet<>();
    if (selection.propositionTotal()) {
      if (!actor.partners() || props.size() != 1 || selection.propositionId() == null) throw DashboardAccess.denied();
      var proposition = props.get(0);
      if (proposition.getEnterpriseId() == null || !proposition.getEnterpriseId().toHexString().equals(actor.tenant().enterpriseId())) throw DashboardAccess.denied();
      selected.addAll(participants(proposition)); selected.retainAll(actor.tenantScope());
    } else {
      String tenant = selection.tenantId() == null ? actor.tenant().id() : selection.tenantId();
      if (!actor.tenantScope().contains(tenant)) throw DashboardAccess.denied();
      var reference = tenants.propositionReference(tenant); if (reference.deleted()) throw DashboardAccess.denied();
      selected.add(tenant);
    }
    final var selectedTenants = Set.copyOf(selected);
    props = props.stream().filter(p -> !Collections.disjoint(participants(p), selectedTenants)).toList();
    if (selection.propositionId() != null && props.isEmpty()) throw DashboardAccess.error(HttpStatus.NOT_FOUND, "Proposition is not distributed to selected Tenant");
    var ids = new HashSet<String>(); props.forEach(p -> ids.add(p.getIdAsString()));
    var actual = data.actual(selectedTenants, ids, selection.year(), selection.currency());
    // A Tenant aggregate must also belong to the selected Proposition's distribution scope.
    var propMap = new HashMap<String, PropositionEntity>(); props.forEach(p -> propMap.put(p.getIdAsString(), p));
    actual = actual.stream().filter(r -> propMap.containsKey(r.propositionId()) && participants(propMap.get(r.propositionId())).contains(r.tenantId())).toList();
    return new Context(actor, props, selectedTenants, actual);
  }
  private void validate(Selection s) {
    if (s.year() < 1970 || s.year() > 9999 || s.period() == null || s.currency() == null) throw bad("Year, period and currency are required");
    try { if (!Currency.getInstance(s.currency()).getCurrencyCode().equals(s.currency())) throw bad("Invalid currency"); }
    catch (IllegalArgumentException invalid) { throw bad("Invalid currency"); }
    if (s.period() == Period.MONTHLY && (s.month() == null || s.month() < 1 || s.month() > 12)
        || s.period() == Period.QUARTERLY && (s.quarter() == null || s.quarter() < 1 || s.quarter() > 4)) throw bad("Invalid period");
  }
  public Summary summary(Selection selection) { return summarize(context(selection), selection); }
  private Summary summarize(Context context, Selection selection) {
    List<DashboardAggregation.Target> targets = new ArrayList<>();
    for (var proposition : context.propositions()) {
      var definition = proposition.getDefinition();
      boolean differentCurrency = definition.currency() != null && !definition.currency().isBlank() && !definition.currency().equals(selection.currency());
      if (selection.propositionId() == null && differentCurrency && context.actual().stream().noneMatch(r ->
          r.propositionId().equals(proposition.getIdAsString()) && selection.currency().equals(r.currency())
              && selection.months().contains(r.month()) && r.totalSalesVolume() > 0)) continue;
      boolean full = selection.propositionTotal() && context.tenantIds().equals(participants(proposition));
      if (full && definition.expectMetrics() != null && !definition.expectMetrics().isEmpty()) {
        targets.add(new DashboardAggregation.Target(definition.currency(), definition.expectMetrics()));
      } else for (String tenant : context.tenantIds()) if (participants(proposition).contains(tenant)) {
        var matching = (definition.partnerMetrics() == null ? List.<com.ecofood.core.dto.PartnerMetricsRequest>of() : definition.partnerMetrics()).stream().filter(m -> tenant.equals(m.tenantId())).toList();
        if (matching.size() > 1) throw DashboardAccess.error(HttpStatus.CONFLICT, "Duplicate PartnerMetrics");
        targets.add(new DashboardAggregation.Target(definition.currency(), matching.isEmpty() ? List.of() : matching.get(0).expectMetrics()));
      }
    }
    // A currency mismatch for a Proposition with Actual cannot be hidden by other matching targets.
    return aggregation.summarize(selection, context.tenantIds(), targets, context.actual());
  }
  public List<Summary> trends(Selection selection) {
    Context context = context(selection); List<Summary> result = new ArrayList<>();
    int count = selection.period() == Period.MONTHLY ? 12 : selection.period() == Period.QUARTERLY ? 4 : 1;
    for (int i = 1; i <= count; i++) result.add(summarize(context, new Selection(selection.tenantId(), selection.propositionId(), selection.currency(), selection.year(), selection.period(), selection.period() == Period.MONTHLY ? i : null, selection.period() == Period.QUARTERLY ? i : null, selection.propositionTotal())));
    return List.copyOf(result);
  }
  public Comparison comparison(Selection selection) {
    var context = context(selection); var summary = summarize(context, selection);
    List<Breakdown> byTenant = new ArrayList<>(), byProposition = new ArrayList<>();
    for (String tenant : context.tenantIds().stream().sorted().toList()) {
      var individual = new Selection(tenant, selection.propositionId(), selection.currency(), selection.year(), selection.period(), selection.month(), selection.quarter(), false);
      byTenant.add(new Breakdown(tenant, selection.propositionId(), summarize(new Context(context.actor(), context.propositions(), Set.of(tenant), context.actual()), individual)));
    }
    for (var prop : context.propositions()) {
      var individual = new Selection(selection.tenantId(), prop.getIdAsString(), selection.currency(), selection.year(), selection.period(), selection.month(), selection.quarter(), selection.propositionTotal());
      byProposition.add(new Breakdown(null, prop.getIdAsString(), summarize(new Context(context.actor(), List.of(prop), context.tenantIds(), context.actual()), individual)));
    }
    return new Comparison(summary, List.copyOf(byTenant), List.copyOf(byProposition));
  }
  public Filters filters() {
    var actor = access.current(); var props = data.propositions(actor);
    var options = props.stream().filter(p -> !Collections.disjoint(participants(p), actor.tenantScope())).map(p -> new PropositionOption(p.getIdAsString(), p.getDefinition().name(), participants(p).stream().filter(actor.tenantScope()::contains).sorted().toList(), p.getDefinition().currency(), actor.partners() && p.getEnterpriseId() != null && p.getEnterpriseId().toHexString().equals(actor.tenant().enterpriseId()))).toList();
    Set<String> propIds = new HashSet<>(); options.forEach(p -> propIds.add(p.id()));
    var participantsByProp = new HashMap<String, Set<String>>(); props.forEach(p -> participantsByProp.put(p.getIdAsString(), participants(p)));
    var rows = data.actual(actor.tenantScope(), propIds, null, null).stream()
        .filter(r -> participantsByProp.getOrDefault(r.propositionId(), Set.of()).contains(r.tenantId())).toList();
    Set<String> currencies = new TreeSet<>(); Set<Integer> years = new TreeSet<>();
    years.add(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).getYear());
    rows.forEach(r -> { currencies.add(r.currency()); years.add(r.year()); });
    for (var prop : props) {
      if (prop.getDefinition().currency() != null && !prop.getDefinition().currency().isBlank()) currencies.add(prop.getDefinition().currency());
      if (prop.getDefinition().expectMetrics() != null) prop.getDefinition().expectMetrics().forEach(m -> { if (m.year() != null) years.add(m.year()); });
      if (prop.getDefinition().partnerMetrics() != null) prop.getDefinition().partnerMetrics().stream().filter(m -> actor.tenantScope().contains(m.tenantId())).forEach(m -> { if (m.expectMetrics() != null) m.expectMetrics().forEach(period -> { if (period.year() != null) years.add(period.year()); }); });
    }
    var tenantOptions = actor.tenantScope().stream().sorted().map(tenants::propositionReference).filter(t -> !t.deleted()).map(t -> new Option(t.id(), t.name())).toList();
    return new Filters(actor.tenant().id(), tenantOptions, options, List.copyOf(currencies), List.copyOf(years), false, actor.partners());
  }
  private static org.springframework.web.server.ResponseStatusException bad(String message) { return DashboardAccess.error(HttpStatus.BAD_REQUEST, message); }
}
