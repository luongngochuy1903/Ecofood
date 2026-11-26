package com.ecofood.app.userACL.service;
import com.ecofood.core.client.*;
import com.ecofood.core.dto.DashboardDtos.Option;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import com.ecofood.core.service.DataAccessRuleService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class DataAccessRuleOptionsService {
  private final DataAccessRuleService rules;
  private final CoreDashboardPropositionClient propositions;
  private final CoreDashboardInvoiceClient invoices;
  public List<Option> entities(String tenantId, AccessEntityTypeEnum type) {
    rules.eligibleUsers(tenantId); // Reuse the same rule-management Tenant authorization.
    if (type == AccessEntityTypeEnum.BUSINESS_DASHBOARD)
      return propositions.find(tenantId, null).stream().map(p -> new Option(p.id(), p.name())).toList();
    if (type == AccessEntityTypeEnum.INVOICE_DASHBOARD) {
      List<Option> result = new ArrayList<>();
      int page = 0;
      org.springframework.data.domain.Page<com.ecofood.core.dto.DashboardInvoice> rows;
      do {
        rows = invoices.dashboard(tenantId, null, page++, 100);
        rows.forEach(i -> result.add(new Option(i.id(), i.invoiceNumber())));
      } while (rows.hasNext());
      return result;
    }
    return List.of();
  }
}
