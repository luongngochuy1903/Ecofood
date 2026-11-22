package com.ecofood.app.orchestrator.service;

import com.ecofood.app.config.*;
import com.ecofood.app.orchestrator.repository.PlanSaleOrchestratorRepository;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.transaction.dto.BatchTransactionDtos;
import com.ecofood.app.transaction.entity.InvoicePlanLine;
import com.ecofood.app.transaction.service.InvoiceSnapshotFactory;
import com.ecofood.core.dto.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Uses the existing ACTIVE version and distribution guards, without changing UC-014 schemas. */
@Service
@RequiredArgsConstructor
public class BatchSalePlanResolver {
  private final PlanSaleOrchestratorRepository orchestrators;
  private final PlanSaleAccess access;
  private final InvoiceSnapshotFactory snapshots;

  public InvoicePlanLine resolve(String tenant, String subject, String number,
      BatchTransactionDtos.Item item, Instant receivedAt) {
    var row=orchestrators.findByIdAndTenantId(item.orchestratorId(),tenant)
        .orElseThrow(()->new ForbiddenException("Orchestrator unavailable in this Tenant"));
    if(!Objects.equals(item.planId(),row.getPlanId())) throw new BadRequestException("Plan does not match Orchestrator");
    var version=PlanSaleOrchestratorServiceImpl.active(row);
    var scope=access.require(tenant,row.getPropositionId(),row.getPlanId()); access.active(scope);
    String currency=access.currency(scope);
    try { if(Currency.getInstance(currency).getDefaultFractionDigits()<0) throw new IllegalArgumentException(); }
    catch(RuntimeException invalid) { throw new BadRequestException("Plan currency unavailable"); }
    var binding=new SaleOrchestratorConfig(tenant,subject,tenant,row.getPlanId(),row.getPropositionId(),true,true,true,Set.of(currency),version.configuration().maxItems());
    // Internal snapshot adapter only; no sale timestamp is invented or persisted on the Invoice.
    var request=new SubmitTransactionRequest(number,null,currency,item.amount(),List.of());
    var prepared=new PreparedSaleTransaction(row.getId(),version.version(),binding,request,Map.of(),Set.of());
    return new InvoicePlanLine(item.lineId(),row.getId(),row.getPlanId(),scope.plan().getName(),
        item.quantity().longValueExact(),item.unitPrice(),item.amount(),snapshots.capture(tenant,prepared,receivedAt),false);
  }
}
