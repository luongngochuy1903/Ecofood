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
  private final DashboardAccess access;
  private final com.ecofood.core.client.CoreDashboardInvoiceClient invoices;
  @GetMapping("/access")
  public java.util.Map<String, Boolean> dashboardAccess(@RequestParam com.ecofood.core.enums.AccessEntityTypeEnum type) {
    if (!List.of(com.ecofood.core.enums.AccessEntityTypeEnum.BUSINESS_DASHBOARD,
        com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,
        com.ecofood.core.enums.AccessEntityTypeEnum.CUSTOMER_DASHBOARD).contains(type))
      throw DashboardAccess.error(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid dashboard type");
    return java.util.Map.of("allowed", !access.authorizedTenants(type).isEmpty());
  }
  @GetMapping("/invoices/tenants")
  public List<Option> invoiceTenants() {
    return access.authorizedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD).stream()
        .map(a -> new Option(a.tenant().id(), a.tenant().name())).toList();
  }
  @GetMapping("/invoices")
  public org.springframework.data.domain.Page<com.ecofood.core.dto.DashboardInvoice> invoices(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String tenantId) {
    var actor = access.current(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD, tenantId);
    if (actor.tenantScope().isEmpty()) return org.springframework.data.domain.Page.empty();
    return invoices.dashboard(actor.tenant().id(), actor.entityIds(), page, size);
  }
  @GetMapping("/invoices/{id}")
  public com.ecofood.core.dto.DashboardInvoiceDetail invoiceDetail(@PathVariable String id,
      @RequestParam(required = false) String tenantId) {
    var actor = access.current(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD, tenantId);
    if (actor.tenantScope().isEmpty() || actor.entityIds() != null && !actor.entityIds().contains(id))
      throw DashboardAccess.error(org.springframework.http.HttpStatus.NOT_FOUND, "Invoice not found");
    return invoices.detail(actor.tenant().id(), id);
  }
  @GetMapping("/filters") public Filters filters() { return service.filters(); }
  @GetMapping({"/summary", "/widget-data"}) public Summary summary(@RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String propositionId, @RequestParam String currency,
      @RequestParam(required = false) Integer year, @RequestParam(defaultValue = "QUARTERLY") DashboardPeriodEnum period,
      @RequestParam(required = false) Integer month, @RequestParam(required = false) Integer quarter,
      @RequestParam(defaultValue = "false") boolean propositionTotal, @RequestParam(required = false) String planId,
      @RequestParam(required = false) List<String> tenantIds) {
    rejectPlan(planId); return service.summary(selection(tenantId, propositionId, currency, year, period, new PeriodInput(month, quarter), propositionTotal, tenantIds));
  }
  @GetMapping("/trends") public List<Summary> trends(@RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String propositionId, @RequestParam String currency,
      @RequestParam(required = false) Integer year, @RequestParam(defaultValue = "QUARTERLY") DashboardPeriodEnum period,
      @RequestParam(required = false) Integer month, @RequestParam(required = false) Integer quarter,
      @RequestParam(defaultValue = "false") boolean propositionTotal, @RequestParam(required = false) String planId,
      @RequestParam(required = false) List<String> tenantIds) {
    rejectPlan(planId); return service.trends(selection(tenantId, propositionId, currency, year, period, new PeriodInput(month, quarter), propositionTotal, tenantIds));
  }
  @GetMapping("/comparison") public Comparison comparison(@RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String propositionId, @RequestParam String currency,
      @RequestParam(required = false) Integer year, @RequestParam(defaultValue = "QUARTERLY") DashboardPeriodEnum period,
      @RequestParam(required = false) Integer month, @RequestParam(required = false) Integer quarter,
      @RequestParam(defaultValue = "false") boolean propositionTotal, @RequestParam(required = false) String planId,
      @RequestParam(required = false) List<String> tenantIds) {
    rejectPlan(planId); return service.comparison(selection(tenantId, propositionId, currency, year, period, new PeriodInput(month, quarter), propositionTotal, tenantIds));
  }
  private record PeriodInput(Integer month, Integer quarter) {}

  private Selection selection(String tenant, String prop, String currency, Integer year,
      DashboardPeriodEnum period, PeriodInput input, boolean total, List<String> tenantIds) {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
    int resolvedYear = year == null ? today.getYear() : year;
    Integer month = null;
    Integer quarter = null;
    if (period == DashboardPeriodEnum.MONTHLY) {
      month = input.month() == null ? today.getMonthValue() : input.month();
    }
    if (period == DashboardPeriodEnum.QUARTERLY) {
      quarter = input.quarter() == null ? (today.getMonthValue() - 1) / 3 + 1 : input.quarter();
    }
    return new Selection(blank(tenant), blank(prop), currency, resolvedYear, period, month, quarter, total, tenantIds);
  }
  private String blank(String value) { return value == null || value.isBlank() ? null : value; }
  private void rejectPlan(String plan) {
    if (plan != null && !plan.isBlank()) throw DashboardAccess.error(org.springframework.http.HttpStatus.BAD_REQUEST, "Plan filter is unavailable: ActualMetrics has no Plan dimension");
  }
}
