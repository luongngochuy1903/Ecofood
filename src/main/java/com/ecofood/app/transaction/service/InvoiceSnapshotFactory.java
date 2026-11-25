package com.ecofood.app.transaction.service;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.transaction.entity.InvoiceSnapshot;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.client.CoreProductQueryClient;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Prices invoices from the Plan's own costing now — a Plan is a full,
 * independently-priced clone of the source product, not a thin override, so
 * its costing is read live via CoreProductProcessClient (keyed by the
 * Plan's own id) rather than from a cached sourceProcess snapshot on the
 * Proposition, which no longer carries pricing at all.
 */
@Component
@RequiredArgsConstructor
public class InvoiceSnapshotFactory {
  private static final ZoneId ACCOUNTING_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private final PlanSaleAccess access;
  private final CoreProductProcessClient productProcesses;
  private final CoreProductQueryClient products;

  public InvoiceSnapshot capture(String tenant, PreparedSaleTransaction prepared, Instant receivedAt) {
    var binding = prepared.binding();
    if (!Objects.equals(tenant, binding.tenantId()))
      throw new ForbiddenException("Invalid snapshot Tenant");
    // Reuse the existing tenant/Plan membership lookup, without reading the live Product catalog.
    var scope = access.require(tenant, binding.propositionId(), binding.planId());
    var proposition = scope.proposition();
    var plan = scope.plan();
    var missing = new ArrayList<String>();
    missing.add("NET_REVENUE_UNVERIFIED");
    var source = planProcess(plan, missing);
    var cost = costing(source, missing);
    validateCosting(source, cost, prepared.transaction().currency(), missing);
    var definition = proposition.getDefinition();
    if (definition.enterpriseProfitShare() == null || definition.platformProfitShare() == null
        || definition.distributionProfitShare() == null) missing.add("PROFIT_SHARE_MISSING");
    var accountingDate = receivedAt.atZone(ACCOUNTING_ZONE);
    var productId = proposition.getProductId() == null ? null : proposition.getProductId().toHexString();
    // The Plan's own bound Product version — the Proposition no longer pins
    // one — resolved live rather than cached, same reasoning as CatalogData.
    var sourceProcessId = productId == null || plan.getProductVersion() == null
        ? null
        : products.processes(productId).stream()
            .filter(x -> x.version() == plan.getProductVersion())
            .findFirst()
            .map(com.ecofood.core.dto.ProductProcessResponse::id)
            .orElse(null);
    return new InvoiceSnapshot(
        1, proposition.getIdAsString(), proposition.getRevision(), tenant,
        binding.distributionPartnerId(), plan.getIdAsString(), plan.getVersion(),
        prepared.orchestratorId(), prepared.orchestratorVersion(),
        productId,
        sourceProcessId,
        plan.getProductVersion() == null ? 0 : plan.getProductVersion(),
        source == null ? null : source.revision(),
        cost == null ? null : new InvoiceSnapshot.Costing(
            cost.id(), cost.productProcessId(), cost.processRevision(), cost.currency(),
            cost.ingredientCost(), cost.packagingCost(), cost.laborCost(), cost.operationCost(),
            cost.marketingFee(), cost.targetMargin(), cost.tax(), cost.totalCost(),
            cost.basePrice(), cost.sellingPrice()),
        new InvoiceSnapshot.ProfitShare(definition.enterpriseProfitShare(),
            definition.platformProfitShare(), definition.distributionProfitShare()),
        // Plan no longer has a separate market-configuration layer — its costing
        // above (sellingPrice/tax) is the one pricing source now.
        null,
        prepared.transaction().currency(), receivedAt, ACCOUNTING_ZONE.getId(),
        accountingDate.getMonthValue(), accountingDate.getYear(), "MISSING_DATA", missing,
        null, null, null);
  }

  private ProductProcessResponse planProcess(Plan plan, List<String> missing) {
    try {
      return productProcesses.get(plan.getIdAsString());
    } catch (ResponseStatusException ex) {
      missing.add("PRODUCT_PROCESS_MISSING");
      return null;
    }
  }

  private ProductCostingAttributeResponse costing(ProductProcessResponse source, List<String> missing) {
    if (source == null) return null;
    if (source.costing() == null
        || source.costing().processElementAttribute() == null
        || source.costing().processElementAttribute().size() != 1) {
      missing.add("COSTING_MISSING");
      return null;
    }
    return source.costing().processElementAttribute().get(0);
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
