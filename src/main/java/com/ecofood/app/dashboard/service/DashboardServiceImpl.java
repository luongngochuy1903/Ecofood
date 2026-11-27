package com.ecofood.app.dashboard.service;

import static com.ecofood.core.dto.DashboardDtos.*;
import com.ecofood.app.dashboard.repository.DashboardData;
import com.ecofood.app.dashboard.security.DashboardAccess;
import com.ecofood.core.dto.DashboardActor;
import com.ecofood.core.dto.DashboardProposition;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import com.ecofood.core.enums.DashboardPeriodEnum;
import com.ecofood.core.service.DashboardQueryService;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardQueryService {
  private static final AccessEntityTypeEnum BUSINESS = AccessEntityTypeEnum.BUSINESS_DASHBOARD;
  private final DashboardAccess access;
  private final DashboardData data;
  private final DashboardAggregation aggregation;
  private record Context(Set<String> tenants, List<DashboardProposition> propositions) {}

  private void validate(Selection s) {
    if (s.year() < 1970 || s.year() > 9999 || s.period() == null || s.currency() == null
        || s.period() == DashboardPeriodEnum.MONTHLY
        || s.period() == DashboardPeriodEnum.QUARTERLY && (s.quarter() == null || s.quarter() < 1 || s.quarter() > 4))
      throw DashboardAccess.error(HttpStatus.BAD_REQUEST, "Select a valid quarter or year and currency");
    try { Currency.getInstance(s.currency()); }
    catch (IllegalArgumentException invalid) { throw DashboardAccess.error(HttpStatus.BAD_REQUEST, "Invalid currency"); }
    if (s.propositionTotal()) throw DashboardAccess.denied();
    if (s.tenantIds() != null && (s.tenantId() != null || s.tenantIds().isEmpty()
        || s.tenantIds().stream().anyMatch(id -> id == null || id.isBlank())))
      throw DashboardAccess.error(HttpStatus.BAD_REQUEST, "Select at least one Tenant; use tenantIds or tenantId, not both");
  }
  private Context context(Selection s) {
    validate(s);
    List<DashboardActor> actors = new ArrayList<>();
    if (s.tenantIds() == null) actors.add(access.current(BUSINESS, s.tenantId()));
    else for (String id : s.tenantIds()) {
      var actor = access.current(BUSINESS, id);
      if (actor.tenantScope().isEmpty()) throw DashboardAccess.denied();
      actors.add(actor);
    }
    // Authorize every selected Tenant before reading any Proposition data.
    Map<String, DashboardProposition> propositions = new LinkedHashMap<>();
    Set<String> tenants = new LinkedHashSet<>();
    for (var actor : actors) {
      tenants.add(actor.tenant().id());
      if (actor.tenantScope().isEmpty()) continue;
      for (var p : data.propositions(actor)) {
        if (s.propositionId() == null || s.propositionId().equals(p.id())) propositions.merge(p.id(), p, DashboardServiceImpl::merge);
      }
    }
    return new Context(Set.copyOf(tenants), List.copyOf(propositions.values()));
  }
  private static DashboardProposition merge(DashboardProposition a, DashboardProposition b) {
    // Same Proposition may be returned for several Tenants. Legacy global rows must be counted once.
    var rows = java.util.stream.Stream.concat(a.actualMetrics().stream(), b.actualMetrics().stream()).distinct().toList();
    return new DashboardProposition(a.id(), a.name(), a.currency(), rows);
  }
  @Override public Summary summary(Selection s) {
    var context = context(s);
    return aggregation.embedded(s, context.tenants(), context.propositions());
  }
  @Override public List<Summary> trends(Selection s) {
    var context = context(s);
    List<Summary> result = new ArrayList<>();
    for (int q = 1; q <= (s.period() == DashboardPeriodEnum.YEARLY ? 1 : 4); q++) {
      var period = new Selection(s.tenantId(), s.propositionId(), s.currency(), s.year(), s.period(), null,
          s.period() == DashboardPeriodEnum.YEARLY ? null : q, false, s.tenantIds());
      result.add(aggregation.embedded(period, context.tenants(), context.propositions()));
    }
    return result;
  }
  @Override public Comparison comparison(Selection s) { return new Comparison(summary(s), List.of(), List.of()); }
  @Override public Filters filters() {
    var session = access.current();
    var options = access.tenantOptions(BUSINESS);
    var actors = options.stream().filter(a -> !a.tenantScope().isEmpty()).toList();
    Map<String, DashboardProposition> props = new LinkedHashMap<>();
    Map<String, Set<String>> propTenants = new HashMap<>();
    for (var actor : actors) for (var p : data.propositions(actor)) {
      props.merge(p.id(), p, DashboardServiceImpl::merge);
      propTenants.computeIfAbsent(p.id(), ignored -> new TreeSet<>()).add(actor.tenant().id());
    }
    Set<String> currencies = new TreeSet<>(); Set<Integer> years = new TreeSet<>();
    years.add(LocalDate.now().getYear());
    for (var p : props.values()) {
      if (p.currency() != null) currencies.add(p.currency());
      for (var row : p.actualMetrics()) {
        if (row.currency() != null) currencies.add(row.currency());
        if (row.year() != null) years.add(row.year());
      }
    }
    return new Filters(session.tenant().id(), options.stream().map(a -> new Option(a.tenant().id(), a.tenant().name(), !a.tenantScope().isEmpty())).toList(),
        props.values().stream().map(p -> new PropositionOption(p.id(), p.name(), List.copyOf(propTenants.get(p.id())), p.currency(), false)).toList(),
        List.copyOf(currencies), List.copyOf(years), false, false);
  }
}
