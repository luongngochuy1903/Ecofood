package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.entity.InvoicePlanLine;
import com.ecofood.app.transaction.entity.InvoiceSnapshot;
import com.ecofood.core.dto.EconomicMetricsRequest;
import com.ecofood.core.enums.TransactionStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Writes the only live actuals source in the SAME transaction as the accepted invoices. */
@Service
public class PropositionSalesMetrics {
  public static final int VERSION = 1;
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  public void record(MongoOperations ops, List<Invoice> invoices) {
    var propositions = new LinkedHashMap<ObjectId, PropositionEntity>();
    for (var invoice : invoices) {
      for (var line : invoice.getPlanLines()) {
        var snapshot = line.snapshot();
        if (snapshot == null || !ObjectId.isValid(snapshot.propositionId()))
          throw new IllegalStateException("Invoice line has no Proposition snapshot");
        var id = new ObjectId(snapshot.propositionId());
        var proposition = propositions.computeIfAbsent(id, key -> {
          var p = ops.findById(key, PropositionEntity.class);
          if (p == null || Boolean.TRUE.equals(p.getDeleted()))
            throw new IllegalStateException("Sale Proposition is unavailable");
          if (!Integer.valueOf(VERSION).equals(p.getActualMetricsVersion())) rebuild(ops, p);
          return p;
        });
        add(proposition, invoice, snapshot, line.quantity(), line.amount());
      }
    }
    propositions.values().forEach(ops::save);
  }

  /** Rebuild before inserting a new batch; historical amounts are never added to old aggregates twice. */
  public void rebuild(MongoOperations ops, PropositionEntity proposition) {
    var id = proposition.getIdAsString();
    var criteria = Criteria.where("status").is(TransactionStatus.ACCEPTED);
    criteria.orOperator(Criteria.where("propositionId").is(id),
        Criteria.where("planLines.snapshot.propositionId").is(id));
    var invoices = ops.find(Query.query(criteria), Invoice.class);
    // Legacy quarterly rows cannot be attributed to a tenant without their source invoices.
    // Preserve them for the Proposition's historical display rather than inventing a tenant.
    var legacy = proposition.getActualMetrics() == null ? List.<EconomicMetricsRequest>of()
        : proposition.getActualMetrics();
    proposition.setActualMetrics(new ArrayList<>());
    if (invoices.isEmpty()) {
      proposition.setActualMetrics(legacy);
    } else for (var invoice : invoices) {
      if (invoice.getPlanLines() != null && !invoice.getPlanLines().isEmpty()) {
        for (var line : invoice.getPlanLines()) {
          if (line.snapshot() != null && id.equals(line.snapshot().propositionId()))
            add(proposition, invoice, line.snapshot(), line.quantity(), line.amount());
        }
      } else if (invoice.getSnapshot() != null && id.equals(invoice.getSnapshot().propositionId())) {
        add(proposition, invoice, invoice.getSnapshot(), 1, invoice.getTotalAmount());
      }
    }
    proposition.setActualMetricsVersion(VERSION);
  }

  private void add(PropositionEntity proposition, Invoice invoice, InvoiceSnapshot snapshot,
      long quantity, BigDecimal revenue) {
    if (invoice.getReceivedAt() == null || invoice.getTenantId() == null
        || invoice.getCurrency() == null || quantity < 1 || revenue == null)
      throw new IllegalStateException("Sale metrics dimensions are missing");
    var date = invoice.getReceivedAt().atZone(ZONE);
    var count = BigDecimal.valueOf(quantity);
    var cost = snapshot.costing();
    var ingredient = cost == null ? BigDecimal.ZERO : zero(cost.ingredientCost()).multiply(count);
    var total = cost == null ? BigDecimal.ZERO : Stream.of(cost.ingredientCost(), cost.packagingCost(),
        cost.laborCost(), cost.operationCost(), cost.marketingFee())
        .map(this::zero).reduce(BigDecimal.ZERO, BigDecimal::add).multiply(count);
    var rate = snapshot.profitShare() == null ? BigDecimal.ZERO
        : zero(snapshot.profitShare().platformProfitShare());
    var platform = revenue.multiply(rate).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
    var profit = revenue.subtract(total).subtract(platform);
    var rows = new ArrayList<>(proposition.getActualMetrics() == null
        ? List.<EconomicMetricsRequest>of() : proposition.getActualMetrics());
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i);
      if (Objects.equals(row.tenantId(), invoice.getTenantId())
          && Objects.equals(row.currency(), invoice.getCurrency())
          && Objects.equals(row.year(), date.getYear())
          && Objects.equals(row.month(), date.getMonthValue())) {
        rows.set(i, new EconomicMetricsRequest(date.getMonthValue(), date.getYear(),
            zero(row.totalSalesVolume()).add(count), zero(row.profit()).add(profit),
            zero(row.ingredientCost()).add(ingredient), zero(row.platformCost()).add(platform),
            null, zero(row.revenue()).add(revenue), invoice.getTenantId(), invoice.getCurrency()));
        proposition.setActualMetrics(List.copyOf(rows));
        return;
      }
    }
    rows.add(new EconomicMetricsRequest(date.getMonthValue(), date.getYear(), count, profit,
        ingredient, platform, null, revenue, invoice.getTenantId(), invoice.getCurrency()));
    proposition.setActualMetrics(List.copyOf(rows));
  }

  private BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
