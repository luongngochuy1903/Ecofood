package com.ecofood.app.dashboard.controller;

import static com.ecofood.core.dto.DashboardDtos.*;

import com.ecofood.app.dashboard.security.DashboardAccess;
import com.ecofood.core.enums.DashboardPeriodEnum;
import com.ecofood.core.service.DashboardQueryService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {
  private final DashboardQueryService service;
  @GetMapping("/filters") public Filters filters() { return service.filters(); }
  @GetMapping({"/summary", "/widget-data"}) public Summary summary(@RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String propositionId, @RequestParam String currency,
      @RequestParam(required = false) Integer year, @RequestParam(defaultValue = "QUARTERLY") DashboardPeriodEnum period,
      @RequestParam(required = false) Integer month, @RequestParam(required = false) Integer quarter,
      @RequestParam(defaultValue = "false") boolean propositionTotal, @RequestParam(required = false) String planId) {
    rejectPlan(planId); return service.summary(selection(tenantId, propositionId, currency, year, period, month, quarter, propositionTotal));
  }
  @GetMapping("/trends") public List<Summary> trends(@RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String propositionId, @RequestParam String currency,
      @RequestParam(required = false) Integer year, @RequestParam(defaultValue = "QUARTERLY") DashboardPeriodEnum period,
      @RequestParam(required = false) Integer month, @RequestParam(required = false) Integer quarter,
      @RequestParam(defaultValue = "false") boolean propositionTotal, @RequestParam(required = false) String planId) {
    rejectPlan(planId); return service.trends(selection(tenantId, propositionId, currency, year, period, month, quarter, propositionTotal));
  }
  @GetMapping("/comparison") public Comparison comparison(@RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String propositionId, @RequestParam String currency,
      @RequestParam(required = false) Integer year, @RequestParam(defaultValue = "QUARTERLY") DashboardPeriodEnum period,
      @RequestParam(required = false) Integer month, @RequestParam(required = false) Integer quarter,
      @RequestParam(defaultValue = "false") boolean propositionTotal, @RequestParam(required = false) String planId) {
    rejectPlan(planId); return service.comparison(selection(tenantId, propositionId, currency, year, period, month, quarter, propositionTotal));
  }
  private Selection selection(String tenant, String prop, String currency, Integer year, DashboardPeriodEnum period, Integer month, Integer quarter, boolean total) {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    return new Selection(blank(tenant), blank(prop), currency, year == null ? today.getYear() : year, period,
        period == DashboardPeriodEnum.MONTHLY ? (month == null ? today.getMonthValue() : month) : null,
        period == DashboardPeriodEnum.QUARTERLY ? (quarter == null ? (today.getMonthValue() - 1) / 3 + 1 : quarter) : null, total);
  }
  private String blank(String value) { return value == null || value.isBlank() ? null : value; }
  private void rejectPlan(String plan) {
    if (plan != null && !plan.isBlank()) throw DashboardAccess.error(org.springframework.http.HttpStatus.BAD_REQUEST, "Plan filter is unavailable: ActualMetrics has no Plan dimension");
  }
}
