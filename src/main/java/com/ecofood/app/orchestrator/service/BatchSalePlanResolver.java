package com.ecofood.app.orchestrator.service;

import com.ecofood.app.config.*;
import com.ecofood.app.customer.security.CustomerIdentity;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.orchestrator.repository.PlanSaleOrchestratorRepository;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.transaction.dto.BatchTransactionDtos;
import com.ecofood.app.transaction.entity.InvoicePlanLine;
import com.ecofood.app.transaction.service.InvoiceSnapshotFactory;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.dto.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves each item straight from its planId — the caller never names an Orchestrator directly. */
@Service
@RequiredArgsConstructor
public class BatchSalePlanResolver {
  private final PlanSaleOrchestratorRepository orchestrators;
  private final PlanSaleAccess access;
  private final InvoiceSnapshotFactory snapshots;
  private final CoreProductProcessClient productProcesses;
  private final CustomerIdentity identity;

  public InvoicePlanLine resolve(String tenant, String subject, int index,
      BatchTransactionDtos.Item item, Instant receivedAt) {
    var row = resolveOrchestrator(tenant, item.planId());
    var scope = access.require(tenant, row.getPropositionId(), item.planId());
    access.active(scope);
    String currency = access.currency(scope);
    try { if (Currency.getInstance(currency).getDefaultFractionDigits() < 0) throw new IllegalArgumentException(); }
    catch (RuntimeException invalid) { throw new BadRequestException("Plan currency unavailable"); }
    BigDecimal unitPrice = resolveUnitPrice(row.getPlanItems(), scope, item);
    requireAdditionalInfo(row.getAdditionalInfoFields(), item);
    BigDecimal amount = unitPrice.multiply(item.quantity());
    var binding = new SaleOrchestratorConfig(tenant, subject, tenant, item.planId(), row.getPropositionId(),
        true, true, true, Set.of(currency), Integer.MAX_VALUE);
    String lineId = "item-" + index;
    // Internal snapshot adapter only; no sale timestamp is invented or
    // persisted on the Invoice. Its own invoiceNumber slot is unused by
    // InvoiceSnapshotFactory — there is no caller-supplied invoice id to
    // pass through here, so lineId fills it purely to satisfy the shape.
    var request = new SubmitTransactionRequest(lineId, null, currency, amount, List.of());
    var prepared = new PreparedSaleTransaction(row.getId(), 1, binding, request, Map.of(), Set.of());
    return new InvoicePlanLine(lineId, row.getId(), item.planId(), planName(item.planId()),
        item.quantity().longValueExact(), unitPrice, amount,
        snapshots.capture(tenant, prepared, receivedAt), item.actualPrice() != null, item.additionalInfo());
  }

  private PlanSaleOrchestrator resolveOrchestrator(String tenant, String planId) {
    var candidates = new LinkedHashMap<String, PlanSaleOrchestrator>();
    orchestrators.findByTenantIdAndPlanIdsContaining(tenant, planId)
        .ifPresent(row -> candidates.put(row.getId(), row));
    for (var row : orchestrators.findByPlanIdsContaining(planId)) {
      if (candidates.containsKey(row.getId()) || tenant.equals(row.getTenantId())) continue;
      if (access.canUseEnterpriseConfiguration(
          tenant, row.getPropositionId(), planId, row.getTenantId())) {
        candidates.put(row.getId(), row);
      }
    }
    if (candidates.isEmpty())
      throw new BadRequestException("Plan is not configured for sale: " + planId);
    // The most recently changed authorized configuration wins, regardless of its owner.
    // Stable tie-breakers keep selection independent of MongoDB result order.
    return candidates.values().stream().max(
        Comparator.comparing((PlanSaleOrchestrator row) ->
                row.getUpdatedAt() != null ? row.getUpdatedAt()
                    : row.getCreatedAt() != null ? row.getCreatedAt() : Instant.MIN)
            .thenComparing(PlanSaleOrchestrator::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(PlanSaleOrchestrator::getId)).orElseThrow();
  }

  /**
   * actualPrice falls back to the Plan's own defined selling price only
   * when the owning Orchestrator's planItems config leaves it optional
   * (the default); marking it required there means a submitted item must
   * always supply its own actualPrice, no fallback.
   */
  private BigDecimal resolveUnitPrice(
      List<SaleConfigurationField> planItems, PlanSaleAccess.Scope scope, BatchTransactionDtos.Item item) {
    boolean actualPriceRequired = planItems.stream()
        .filter(f -> f.key().equals("actualPrice"))
        .findFirst()
        .map(SaleConfigurationField::required)
        .orElse(false);
    if (actualPriceRequired) {
      if (item.actualPrice() == null)
        throw new BadRequestException("actualPrice is required for Plan: " + item.planId());
      return item.actualPrice();
    }
    BigDecimal unitPrice = item.actualPrice() != null ? item.actualPrice() : access.sellingPrice(scope);
    if (unitPrice == null)
      throw new BadRequestException("Plan has no defined selling price; actualPrice is required: " + item.planId());
    return unitPrice;
  }

  /** Each required Additional Info field the owning Orchestrator configured must carry a non-null value. */
  private void requireAdditionalInfo(
      List<SaleConfigurationField> fields, BatchTransactionDtos.Item item) {
    for (var field : fields) {
      if (!field.required()) continue;
      var value = item.additionalInfo() == null ? null : item.additionalInfo().get(field.key());
      if (value == null)
        throw new BadRequestException(
            "Missing required additionalInfo field for Plan " + item.planId() + ": " + field.key());
    }
  }

  /** Validated once per Receipt, per distinct Orchestrator its items touch — see BatchTransactionService.resolveLines. */
  public void requireInvoiceHolder(String orchestratorId, Map<String, Object> invoiceHolder) {
    var row = orchestrators.findById(orchestratorId).orElse(null);
    if (row == null) return;
    for (var field : row.getInvoiceHolder()) {
      if (!field.required()) continue;
      var value = invoiceHolder == null ? null : invoiceHolder.get(field.key());
      if (value == null)
        throw new BadRequestException("Missing required invoiceHolder field: " + field.key());
    }
  }

  /**
   * Best-effort Invoice-to-Customer auto-link: only when the owning
   * Orchestrator has createCustomer enabled, matches the submitted
   * invoiceHolder's externalId against Customer.externalIds. No match (or
   * more than one) returns empty — this never fails the submission, see
   * CustomerIdentity.matchByExternalId.
   */
  public Optional<String> matchCustomer(String tenant, String orchestratorId, Map<String, Object> invoiceHolder) {
    var row = orchestrators.findById(orchestratorId).orElse(null);
    if (row == null || !row.isCreateCustomer() || invoiceHolder == null) return Optional.empty();
    Object raw = invoiceHolder.get("externalId");
    if (!(raw instanceof String externalId) || externalId.isBlank()) return Optional.empty();
    return identity.matchByExternalId(tenant, externalId);
  }

  /** The Plan's own name now lives on its cloned product process, not on the embedded Plan itself. */
  private String planName(String planId) {
    var content = productProcesses.get(planId);
    if (content.definition() == null || content.definition().processElementAttribute() == null
        || content.definition().processElementAttribute().isEmpty()) return null;
    return content.definition().processElementAttribute().get(0).productName();
  }
}
