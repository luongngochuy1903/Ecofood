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
    if (!"PLAN_SALE_BATCH".equals(invoice.getType())) return List.of(calculate(invoice));
    if (invoice.getPlanLines()==null || invoice.getPlanLines().isEmpty())
      throw new KpiProcessingException("BATCH_PLAN_LINES_MISSING",false);
    var results=new ArrayList<KpiContribution>(); var ids=new HashSet<String>();
    for(var line:invoice.getPlanLines().stream().sorted(Comparator.comparing(com.ecofood.app.transaction.entity.InvoicePlanLine::lineId)).toList()) {
      if(line.quantity()<1 || line.quantity()>1000000 || blank(line.lineId()) || !ids.add(line.lineId()) || line.snapshot()==null)
        throw new KpiProcessingException("BATCH_PLAN_LINE_INVALID",false);
      var unit=new Invoice(); unit.setId(invoice.getId()); unit.setTenantId(invoice.getTenantId());
      unit.setDistributionPartnerId(invoice.getDistributionPartnerId()); unit.setPropositionId(line.snapshot().propositionId());
      unit.setPlanId(line.planId()); unit.setOrchestratorId(line.orchestratorId()); unit.setOrchestratorVersion(line.snapshot().orchestratorVersion());
      unit.setReceivedAt(invoice.getReceivedAt()); unit.setCurrency(invoice.getCurrency()); unit.setStatus(invoice.getStatus());
      unit.setPaymentStatus(invoice.getPaymentStatus()); unit.setSnapshot(line.snapshot());
      var contribution=calculate(unit); var missing=new ArrayList<>(contribution.missingData());
      if(!line.saleConfigurationVerified()) missing.add("SALE_CONFIGURATION_TIME_UNVERIFIED");
      String fingerprint;
      try { fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(List.of(contribution.inputFingerprint(),line)))); }
      catch(Exception failure) { throw new IllegalStateException("Cannot fingerprint batch line",failure); }
      results.add(new KpiContribution(invoice.getId(),contribution.key(),VERSION,fingerprint,line.quantity(),
          line.saleConfigurationVerified()?scale(contribution.ingredientCost(),line.quantity()):null,
          line.saleConfigurationVerified()?scale(contribution.platformCost(),line.quantity()):null,
          line.saleConfigurationVerified()?scale(contribution.profit(),line.quantity()):null,missing));
    }
    return List.copyOf(results);
  }
  private BigDecimal scale(BigDecimal amount,long quantity) { return amount==null?null:amount.multiply(BigDecimal.valueOf(quantity)); }

  public KpiContribution calculate(Invoice invoice) {
    if ("PLAN_SALE_BATCH".equals(invoice.getType())) throw new KpiProcessingException("USE_BATCH_CONTRIBUTIONS",false);
    if (invoice.getStatus() != TransactionStatus.ACCEPTED || !"PAID".equals(invoice.getPaymentStatus()))
      throw new KpiProcessingException("INVOICE_NOT_ACCEPTED_PAID", false);
    if (blank(invoice.getId()) || invoice.getReceivedAt() == null)
      throw new KpiProcessingException("INVOICE_ID_OR_RECEIVED_AT_MISSING", false);
    final MetricsKey key;
    final int digits;
    try {
      var date = invoice.getReceivedAt().atZone(ZONE);
      key = new MetricsKey(invoice.getPropositionId(), invoice.getTenantId(), date.getYear(),
          date.getMonthValue(), invoice.getCurrency());
      digits = Currency.getInstance(key.currency()).getDefaultFractionDigits();
      if (digits < 0) throw new IllegalArgumentException();
    } catch (IllegalArgumentException ex) {
      throw new KpiProcessingException("INVOICE_METRICS_DIMENSIONS_INVALID", false);
    }
    var missing = new ArrayList<String>();
    var snapshot = invoice.getSnapshot();
    boolean trusted = snapshot != null
        && Objects.equals(snapshot.tenantId(), invoice.getTenantId())
        && Objects.equals(snapshot.distributionPartnerId(), invoice.getDistributionPartnerId())
        && Objects.equals(snapshot.propositionId(), invoice.getPropositionId())
        && Objects.equals(snapshot.planId(), invoice.getPlanId())
        && Objects.equals(snapshot.orchestratorId(), invoice.getOrchestratorId())
        && snapshot.orchestratorVersion() == invoice.getOrchestratorVersion()
        && Objects.equals(snapshot.currency(), invoice.getCurrency());
    if (!trusted) missing.add(snapshot == null ? "SNAPSHOT_MISSING" : "SNAPSHOT_SCOPE_MISMATCH");
    var costing = trusted ? snapshot.costing() : null;
    boolean validCosting = costing != null && !blank(costing.attributeId())
        && !blank(snapshot.productProcessId()) && snapshot.productProcessRevision() != null
        && Objects.equals(costing.productProcessId(), snapshot.productProcessId())
        && Objects.equals(costing.processRevision(), snapshot.productProcessRevision())
        && Objects.equals(costing.currency(), key.currency());
    if (!validCosting) missing.add("COSTING_REFERENCE_OR_CURRENCY_INVALID");
    BigDecimal ingredient = amount(validCosting ? costing.ingredientCost() : null, digits, "INGREDIENT_COST", missing);
    BigDecimal packaging = amount(validCosting ? costing.packagingCost() : null, digits, "PACKAGING_COST", missing);
    BigDecimal labor = amount(validCosting ? costing.laborCost() : null, digits, "LABOR_COST", missing);
    BigDecimal operation = amount(validCosting ? costing.operationCost() : null, digits, "OPERATION_COST", missing);
    BigDecimal marketing = amount(validCosting ? costing.marketingFee() : null, digits, "MARKETING_FEE", missing);
    // totalAmount and approved Plan prices do NOT prove the transaction's net revenue.
    BigDecimal net = amount(trusted && !snapshot.missingData().contains("NET_REVENUE_UNVERIFIED")
        ? snapshot.netRevenue() : null, digits, "NET_REVENUE", missing);
    var shares = trusted ? snapshot.profitShare() : null;
    BigDecimal rate = shares == null ? null : shares.platformProfitShare();
    if (rate == null || rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0) {
      missing.add("PLATFORM_PROFIT_SHARE_INVALID");
      rate = null;
    }
    BigDecimal platform = net == null || rate == null ? null
        : net.multiply(rate).divide(new BigDecimal("100"), digits, RoundingMode.HALF_UP);
    BigDecimal profit = net == null || ingredient == null || packaging == null || labor == null
        || operation == null || marketing == null || platform == null ? null
        : net.subtract(ingredient).subtract(packaging).subtract(labor).subtract(operation)
            .subtract(marketing).subtract(platform).setScale(digits, RoundingMode.HALF_UP);
    return new KpiContribution(invoice.getId(), key, VERSION, fingerprint(invoice), 1,
        ingredient, platform, profit, missing);
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
