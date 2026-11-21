package com.ecofood.app.dashboard;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class DashboardDtos {
  private DashboardDtos() {}
  public enum Period { MONTHLY, QUARTERLY, YEARLY }
  public record Selection(String tenantId, String propositionId, String currency, int year,
                          Period period, Integer month, Integer quarter, boolean propositionTotal) {
    public Set<Integer> months() {
      if (period == Period.MONTHLY) return Set.of(month);
      if (period == Period.QUARTERLY) return Set.of(quarter * 3 - 2, quarter * 3 - 1, quarter * 3);
      return Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }
  }
  public record Metric(
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actual,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expected,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal variance,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal achievementPercent,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal variancePercent,
      String actualStatus, String expectedStatus, String unit, String interpretation,
      long coveredInvoices, long totalInvoices) {}
  public record Summary(Selection selection, List<String> tenantIds, Map<String, Metric> metrics,
                        Instant lastUpdatedAt, String timezone, List<String> notices) {}
  public record Option(String id, String name) {}
  public record PropositionOption(String id, String name, List<String> tenantIds, String expectedCurrency,
                                  boolean canViewTotal) {}
  public record Filters(String currentTenantId, List<Option> tenants, List<PropositionOption> propositions,
                        List<String> currencies, List<Integer> years, boolean planFilterSupported,
                        boolean partnerComparisonAllowed) {}
  public record Breakdown(String tenantId, String propositionId, Summary summary) {}
  public record Comparison(Summary summary, List<Breakdown> byTenant, List<Breakdown> byProposition) {}
}
