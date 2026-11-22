package com.ecofood.app.transaction.service;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.transaction.entity.InvoiceSnapshot;
import com.ecofood.core.dto.PreparedSaleTransaction;
import com.ecofood.core.dto.ProductCostingAttributeResponse;
import com.ecofood.core.dto.ProductProcessResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InvoiceSnapshotFactory {
  private static final ZoneId ACCOUNTING_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private final PlanSaleAccess access;

  public InvoiceSnapshot capture(String tenant, PreparedSaleTransaction prepared, Instant receivedAt) {
    var binding = prepared.binding();
    if (!Objects.equals(tenant, binding.tenantId()))
      throw new ForbiddenException("Invalid snapshot Tenant");
    // Reuse the existing tenant/Plan membership lookup, without reading the live Product catalog.
    var scope = access.require(tenant, binding.propositionId(), binding.planId());
    var proposition = scope.proposition();
    var plan = scope.plan();
    var source = proposition.getSourceProcess();
    boolean sourceMatches = sourceMatches(proposition, source);
    var missing = new ArrayList<String>();
    missing.add("NET_REVENUE_UNVERIFIED");
    var cost = costing(source, sourceMatches, missing);
    validateCosting(source, cost, prepared.transaction().currency(), missing);
    var definition = proposition.getDefinition();
    if (definition.enterpriseProfitShare() == null || definition.platformProfitShare() == null
        || definition.distributionProfitShare() == null) missing.add("PROFIT_SHARE_MISSING");
    var market = plan.getMarketConfiguration();
    var accountingDate = receivedAt.atZone(ACCOUNTING_ZONE);
    return new InvoiceSnapshot(
        1, proposition.getIdAsString(), proposition.getRevision(), tenant,
        binding.distributionPartnerId(), plan.getIdAsString(), plan.getVersion(),
        prepared.orchestratorId(), prepared.orchestratorVersion(),
        proposition.getProductId() == null ? null : proposition.getProductId().toHexString(),
        proposition.getProductProcessId() == null ? null : proposition.getProductProcessId().toHexString(),
        proposition.getProductVersion(), sourceMatches ? source.revision() : null,
        cost == null ? null : new InvoiceSnapshot.Costing(
            cost.id(), cost.productProcessId(), cost.processRevision(), cost.currency(),
            cost.ingredientCost(), cost.packagingCost(), cost.laborCost(), cost.operationCost(),
            cost.marketingFee(), cost.targetMargin(), cost.tax(), cost.totalCost(),
            cost.basePrice(), cost.sellingPrice()),
        new InvoiceSnapshot.ProfitShare(definition.enterpriseProfitShare(),
            definition.platformProfitShare(), definition.distributionProfitShare()),
        market == null ? null : new InvoiceSnapshot.Market(market.currency(), market.salePrice(),
            market.taxRate(), market.surcharge(), market.taxAmount(), market.totalPrice()),
        prepared.transaction().currency(), receivedAt, ACCOUNTING_ZONE.getId(),
        accountingDate.getMonthValue(), accountingDate.getYear(), "MISSING_DATA", missing,
        null, null, null);
  }

  private boolean sourceMatches(PropositionEntity proposition, ProductProcessResponse source) {
    return source != null
        && proposition.getProductProcessId() != null
        && Objects.equals(proposition.getProductProcessId().toHexString(), source.id())
        && proposition.getProductId() != null
        && Objects.equals(proposition.getProductId().toHexString(), source.productId())
        && proposition.getProductVersion() == source.version();
  }

  private ProductCostingAttributeResponse costing(ProductProcessResponse source,
      boolean sourceMatches, List<String> missing) {
    ProductCostingAttributeResponse cost = null;
    if (source == null) {
      missing.add("PRODUCT_PROCESS_MISSING");
    } else if (!sourceMatches) {
      missing.add("PRODUCT_PROCESS_REFERENCE_MISMATCH");
    } else if (source.costing() != null
        && source.costing().processElementAttribute() != null
        && source.costing().processElementAttribute().size() == 1) {
      var candidate = source.costing().processElementAttribute().get(0);
      if (candidate != null
          && Objects.equals(candidate.productProcessId(), source.id())
          && Objects.equals(candidate.processRevision(), source.revision())) {
        cost = candidate;
      } else {
        missing.add("COSTING_REFERENCE_MISMATCH");
      }
    }
    return cost;
  }

  private void validateCosting(ProductProcessResponse source,
      ProductCostingAttributeResponse cost, String currency, List<String> missing) {
    if (cost == null) missing.add("COSTING_MISSING");
    if (source != null && source.revision() == null) missing.add("PRODUCT_PROCESS_REVISION_MISSING");
    if (cost != null && cost.id() == null) missing.add("COSTING_ATTRIBUTE_ID_MISSING");
    if (cost != null && !Objects.equals(cost.currency(), currency))
      missing.add("COSTING_CURRENCY_MISMATCH");
    if (cost != null
        && (cost.ingredientCost() == null || cost.packagingCost() == null
            || cost.laborCost() == null || cost.operationCost() == null
            || cost.marketingFee() == null)) missing.add("COSTING_COMPONENTS_MISSING");
  }
}
