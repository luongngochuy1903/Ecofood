package com.ecofood.app.metrics.service;

import com.ecofood.app.metrics.entity.KpiContribution;
import com.ecofood.app.metrics.entity.MetricsKey;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.entity.InvoiceSnapshot;
import com.ecofood.core.enums.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class KpiCalculator {
  public static final String VERSION = "KPI_V1";
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  /** Legacy Invoices keep their original one-Plan contribution; batch lines carry explicit quantity. */
  public List<KpiContribution> calculateAll(Invoice invoice) {
    if (!invoice.isBatch()) {
      return List.of(calculate(invoice));
    }
    var results = new ArrayList<KpiContribution>();
    var ids = new HashSet<String>();
    var lines = invoice.getPlanLines().stream()
        .sorted(Comparator.comparing(com.ecofood.app.transaction.entity.InvoicePlanLine::lineId)).toList();
    for (var line : lines) {
      validateBatchLine(line, ids);
      results.add(calculateLineContribution(invoice, line));
    }
    return List.copyOf(results);
  }

  private void validateBatchLine(com.ecofood.app.transaction.entity.InvoicePlanLine line, Set<String> ids) {
    if (line.quantity() < 1 || line.quantity() > 1000000 || blank(line.lineId())
        || !ids.add(line.lineId()) || line.snapshot() == null) {
      throw new KpiProcessingException("BATCH_PLAN_LINE_INVALID", false);
    }
  }

  private KpiContribution calculateLineContribution(Invoice invoice,
      com.ecofood.app.transaction.entity.InvoicePlanLine line) {
    var contribution = calculate(planUnit(invoice, line));
    var missing = new ArrayList<>(contribution.missingData());
    if (!line.saleConfigurationVerified()) {
      missing.add("SALE_CONFIGURATION_TIME_UNVERIFIED");
    }
    return new KpiContribution(invoice.getId(), contribution.key(), VERSION,
        fingerprintLine(contribution, line), line.quantity(),
        line.saleConfigurationVerified() ? scale(contribution.ingredientCost(), line.quantity()) : null,
        line.saleConfigurationVerified() ? scale(contribution.platformCost(), line.quantity()) : null,
        line.saleConfigurationVerified() ? scale(contribution.profit(), line.quantity()) : null, missing);
  }

  private Invoice planUnit(Invoice invoice, com.ecofood.app.transaction.entity.InvoicePlanLine line) {
    var unit = new Invoice();
    unit.setId(invoice.getId());
    unit.setTenantId(invoice.getTenantId());
    unit.setDistributionPartnerId(invoice.getDistributionPartnerId());
    unit.setPropositionId(line.snapshot().propositionId());
    unit.setPlanId(line.planId());
    unit.setOrchestratorId(line.orchestratorId());
    unit.setOrchestratorVersion(line.snapshot().orchestratorVersion());
    unit.setReceivedAt(invoice.getReceivedAt());
    unit.setCurrency(invoice.getCurrency());
    unit.setStatus(invoice.getStatus());
    unit.setSnapshot(line.snapshot());
    return unit;
  }

  private String fingerprintLine(KpiContribution contribution,
      com.ecofood.app.transaction.entity.InvoicePlanLine line) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(mapper.writeValueAsBytes(List.of(contribution.inputFingerprint(), line))));
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot fingerprint batch line", failure);
    }
  }
  private BigDecimal scale(BigDecimal amount,long quantity) { return amount==null?null:amount.multiply(BigDecimal.valueOf(quantity)); }

  public KpiContribution calculate(Invoice invoice) {
    validateInvoice(invoice);
    var dimensions = dimensions(invoice);
    var key = dimensions.key();
    int digits = dimensions.digits();
    var missing = new ArrayList<String>();
    var snapshot = verifiedSnapshot(invoice, missing);
    var costing = verifiedCosting(snapshot, key.currency(), missing);
    var costs = costs(costing, digits, missing);
    BigDecimal net = netRevenue(snapshot, digits, missing);
    BigDecimal rate = platformRate(snapshot, missing);
    BigDecimal platform = platformCost(net, rate, digits);
    BigDecimal profit = profit(net, costs, platform, digits);
    return new KpiContribution(invoice.getId(), key, VERSION, fingerprint(invoice), 1,
        costs.ingredient(), platform, profit, missing);
  }

  private void validateInvoice(Invoice invoice) {
    if (invoice.isBatch()) {
      throw new KpiProcessingException("USE_BATCH_CONTRIBUTIONS", false);
    }
    if (invoice.getStatus() != TransactionStatus.ACCEPTED) {
      throw new KpiProcessingException("INVOICE_NOT_ACCEPTED", false);
    }
    if (blank(invoice.getId()) || invoice.getReceivedAt() == null) {
      throw new KpiProcessingException("INVOICE_ID_OR_RECEIVED_AT_MISSING", false);
    }
  }

  private record Dimensions(MetricsKey key, int digits) {}

  private Dimensions dimensions(Invoice invoice) {
    try {
      var date = invoice.getReceivedAt().atZone(ZONE);
      var key = new MetricsKey(invoice.getPropositionId(), invoice.getTenantId(), date.getYear(),
          date.getMonthValue(), invoice.getCurrency());
      int digits = Currency.getInstance(key.currency()).getDefaultFractionDigits();
      if (digits < 0) throw new IllegalArgumentException();
      return new Dimensions(key, digits);
    } catch (IllegalArgumentException invalid) {
      throw new KpiProcessingException("INVOICE_METRICS_DIMENSIONS_INVALID", false);
    }
  }

  private InvoiceSnapshot verifiedSnapshot(Invoice invoice, List<String> missing) {
    var snapshot = invoice.getSnapshot();
    if (matchesInvoice(snapshot, invoice)) return snapshot;
    missing.add(snapshot == null ? "SNAPSHOT_MISSING" : "SNAPSHOT_SCOPE_MISMATCH");
    return null;
  }

  private boolean matchesInvoice(InvoiceSnapshot snapshot, Invoice invoice) {
    return snapshot != null
        && Objects.equals(snapshot.tenantId(), invoice.getTenantId())
        && Objects.equals(snapshot.distributionPartnerId(), invoice.getDistributionPartnerId())
        && Objects.equals(snapshot.propositionId(), invoice.getPropositionId())
        && Objects.equals(snapshot.planId(), invoice.getPlanId())
        && Objects.equals(snapshot.orchestratorId(), invoice.getOrchestratorId())
        && snapshot.orchestratorVersion() == invoice.getOrchestratorVersion()
        && Objects.equals(snapshot.currency(), invoice.getCurrency());
  }

  private InvoiceSnapshot.Costing verifiedCosting(InvoiceSnapshot snapshot, String currency, List<String> missing) {
    var costing = snapshot == null ? null : snapshot.costing();
    boolean valid = costing != null && !blank(costing.attributeId())
        && !blank(snapshot.productProcessId()) && snapshot.productProcessRevision() != null
        && Objects.equals(costing.productProcessId(), snapshot.productProcessId())
        && Objects.equals(costing.processRevision(), snapshot.productProcessRevision())
        && Objects.equals(costing.currency(), currency);
    if (!valid) {
      missing.add("COSTING_REFERENCE_OR_CURRENCY_INVALID");
      return null;
    }
    return costing;
  }

  private record Costs(BigDecimal ingredient, BigDecimal packaging, BigDecimal labor,
                       BigDecimal operation, BigDecimal marketing) {}

  private Costs costs(InvoiceSnapshot.Costing costing, int digits, List<String> missing) {
    return new Costs(
        amount(costing == null ? null : costing.ingredientCost(), digits, "INGREDIENT_COST", missing),
        amount(costing == null ? null : costing.packagingCost(), digits, "PACKAGING_COST", missing),
        amount(costing == null ? null : costing.laborCost(), digits, "LABOR_COST", missing),
        amount(costing == null ? null : costing.operationCost(), digits, "OPERATION_COST", missing),
        amount(costing == null ? null : costing.marketingFee(), digits, "MARKETING_FEE", missing));
  }

  private BigDecimal netRevenue(InvoiceSnapshot snapshot, int digits, List<String> missing) {
    // totalAmount and approved Plan prices do NOT prove the transaction's net revenue.
    BigDecimal net = snapshot != null && !snapshot.missingData().contains("NET_REVENUE_UNVERIFIED")
        ? snapshot.netRevenue() : null;
    return amount(net, digits, "NET_REVENUE", missing);
  }

  private BigDecimal platformRate(InvoiceSnapshot snapshot, List<String> missing) {
    var shares = snapshot == null ? null : snapshot.profitShare();
    BigDecimal rate = shares == null ? null : shares.platformProfitShare();
    if (rate == null || rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
      missing.add("PLATFORM_PROFIT_SHARE_INVALID");
      return null;
    }
    return rate;
  }

  private BigDecimal platformCost(BigDecimal net, BigDecimal rate, int digits) {
    if (net == null || rate == null) return null;
    return net.multiply(rate).divide(new BigDecimal("100"), digits, RoundingMode.HALF_UP);
  }

  private BigDecimal profit(BigDecimal net, Costs costs, BigDecimal platform, int digits) {
    if (net == null || costs.ingredient() == null || costs.packaging() == null || costs.labor() == null
        || costs.operation() == null || costs.marketing() == null || platform == null) return null;
    return net.subtract(costs.ingredient()).subtract(costs.packaging()).subtract(costs.labor())
        .subtract(costs.operation()).subtract(costs.marketing()).subtract(platform)
        .setScale(digits, RoundingMode.HALF_UP);
  }

  private BigDecimal amount(BigDecimal value, int digits, String name, List<String> missing) {
    if (value == null || value.signum() < 0) {
      missing.add(name + "_UNVERIFIED");
      return null;
    }
    return value.setScale(digits, RoundingMode.HALF_UP);
  }

  private String fingerprint(Invoice invoice) {
    try {
      // Stable record structure; never hash unordered custom payload maps or live catalog data.
      var input = new Input(VERSION, invoice.getId(), invoice.getTenantId(), invoice.getPropositionId(),
          invoice.getPlanId(), invoice.getOrchestratorId(), invoice.getOrchestratorVersion(),
          invoice.getReceivedAt(), invoice.getCurrency(), invoice.getSnapshot());
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(mapper.writeValueAsBytes(input)));
    } catch (Exception ex) { throw new IllegalStateException("Cannot fingerprint Invoice KPI inputs", ex); }
  }

  private record Input(String version, String invoiceId, String tenantId, String propositionId,
                       String planId, String orchestratorId, int orchestratorVersion,
                       java.time.Instant receivedAt, String currency, InvoiceSnapshot snapshot) {}

  private boolean blank(String value) { return value == null || value.isBlank(); }
}
