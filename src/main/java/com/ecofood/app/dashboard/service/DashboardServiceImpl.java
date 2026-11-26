package com.ecofood.app.dashboard.service;

import static com.ecofood.core.dto.DashboardDtos.*;
import com.ecofood.app.dashboard.repository.DashboardData;
import com.ecofood.app.dashboard.security.DashboardAccess;
import com.ecofood.core.dto.DashboardProposition;
import com.ecofood.core.dto.EconomicMetricsRequest;
import com.ecofood.core.enums.DashboardPeriodEnum;
import com.ecofood.core.service.DashboardQueryService;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardQueryService {
  private final DashboardAccess access;
  private final DashboardData data;
  private final DashboardAggregation aggregation;

  private List<DashboardProposition> selected(Selection s) {
    var actor = access.current(com.ecofood.core.enums.AccessEntityTypeEnum.BUSINESS_DASHBOARD, s.tenantId());
    if (s.year() < 1970 || s.year() > 9999 || s.period() == null || s.currency() == null
        || s.period() == DashboardPeriodEnum.MONTHLY
        || s.period() == DashboardPeriodEnum.QUARTERLY && (s.quarter() == null || s.quarter() < 1 || s.quarter() > 4))
      throw DashboardAccess.error(HttpStatus.BAD_REQUEST, "Select a valid quarter or year and currency");
    try { Currency.getInstance(s.currency()); } catch (IllegalArgumentException invalid) { throw DashboardAccess.error(HttpStatus.BAD_REQUEST, "Invalid currency"); }
    if (s.propositionTotal() || s.tenantId() != null && !s.tenantId().equals(actor.tenant().id())) throw DashboardAccess.denied();
    if (actor.tenantScope().isEmpty()) return List.of();
    return data.propositions(actor).stream().filter(p -> s.propositionId() == null || s.propositionId().equals(p.id())).toList();
  }
  @Override public Summary summary(Selection s) {
    var props = selected(s);
    String tenant = access.current(com.ecofood.core.enums.AccessEntityTypeEnum.BUSINESS_DASHBOARD, s.tenantId()).tenant().id();
    return aggregation.embedded(s, tenant, props);
  }
  @Override public List<Summary> trends(Selection s) {
    var props = selected(s);
    String tenant = access.current().tenant().id();
    List<Summary> result = new ArrayList<>();
    for (int q = 1; q <= (s.period() == DashboardPeriodEnum.YEARLY ? 1 : 4); q++)
      result.add(aggregation.embedded(new Selection(s.tenantId(), s.propositionId(), s.currency(), s.year(), s.period(), null,
          s.period() == DashboardPeriodEnum.YEARLY ? null : q, false), tenant, props));
    return result;
  }
  @Override public Comparison comparison(Selection s) { return new Comparison(summary(s), List.of(), List.of()); }
  @Override public Filters filters() {
    var session = access.current();
    var actors = access.authorizedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    var actor = actors.isEmpty() ? session : actors.get(0);
    var props = actors.stream().flatMap(a -> data.propositions(a).stream()).collect(java.util.stream.Collectors.toMap(DashboardProposition::id, p -> p, (a, other) -> new DashboardProposition(a.id(), a.name(), a.currency(), java.util.stream.Stream.concat(a.actualMetrics().stream(), other.actualMetrics().stream()).toList()))).values();
    Set<String> currencies = new TreeSet<>(); Set<Integer> years = new TreeSet<>();
    years.add(LocalDate.now().getYear());
    for (var p : props) {
      if (p.currency() != null) currencies.add(p.currency());
      if (p.actualMetrics() != null) for (var row : p.actualMetrics()) {
        if (row.currency() != null) currencies.add(row.currency());
        if (row.year() != null) years.add(row.year());
      }
    }
    return new Filters(actor.tenant().id(), actors.stream().map(a -> new Option(a.tenant().id(), a.tenant().name())).toList(),
        props.stream().map(p -> new PropositionOption(p.id(), p.name(), List.of(actor.tenant().id()), p.currency(), false)).toList(),
        List.copyOf(currencies), List.copyOf(years), false, false);
  }
}
