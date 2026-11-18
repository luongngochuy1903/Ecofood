package com.ecofood.app.customer.repository;

import com.ecofood.app.customer.auth.CustomerIdentityService.CustomerSession;
import com.ecofood.app.customer.dto.CustomerPurchaseFilter;
import com.ecofood.app.customer.dto.CustomerStatisticsResponse;
import com.ecofood.app.transaction.entity.Invoice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bson.Document;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/**
 * Join from ownership first. Use the collection name for aggregate: after replaceRoot, fields belong
 * to Invoice, not CustomerInvoiceLink. Binding the pipeline to Link would remap Invoice/public fields.
 */
@Repository
@RequiredArgsConstructor
public class CustomerPurchaseRepository {
  private final MongoTemplate mongo;

  public Page<Invoice> list(CustomerSession scope, CustomerPurchaseFilter filter, Pageable pageable) {
    var count = pipeline(scope, filter, null); count.add(Aggregation.count().as("total"));
    var totals = mongo.aggregate(Aggregation.newAggregation(count), "customer_invoice_link", Document.class).getUniqueMappedResult();
    long total = totals == null ? 0 : ((Number) totals.get("total")).longValue();
    var rows = pipeline(scope, filter, null);
    rows.add(Aggregation.sort(pageable.getSort())); rows.add(Aggregation.skip(pageable.getOffset())); rows.add(Aggregation.limit(pageable.getPageSize()));
    return new PageImpl<>(mongo.aggregate(Aggregation.newAggregation(rows), "customer_invoice_link", Invoice.class).getMappedResults(), pageable, total);
  }

  public Optional<Invoice> detail(CustomerSession scope, String id) {
    return Optional.ofNullable(mongo.aggregate(Aggregation.newAggregation(pipeline(scope,
        new CustomerPurchaseFilter(null, null, null, null, null), id)), "customer_invoice_link", Invoice.class).getUniqueMappedResult());
  }

  public CustomerStatisticsResponse statistics(CustomerSession scope, CustomerPurchaseFilter filter) {
    return statistics(scope, filter, ZoneId.of("UTC"));
  }

  public CustomerStatisticsResponse statistics(CustomerSession scope, CustomerPurchaseFilter filter, ZoneId timeZone) {
    var operations = pipeline(scope, filter, null);
    // ACCEPTED is a recorded transaction, not evidence of PAID. Unknown/future lifecycle states never count as spending.
    operations.add(Aggregation.match(Criteria.where("status").is("ACCEPTED").and("transactionTime").type(9)));
    Document month = new Document("$dateToString", new Document("format", "%Y-%m").append("date", "$transactionTime").append("timezone", timeZone.getId()));
    operations.add(Aggregation.facet(
        Aggregation.group("currency").count().as("invoiceCount").sum("totalAmount").as("totalAmount"),
        Aggregation.project("invoiceCount", "totalAmount").and("_id").as("currency").andExclude("_id"),
        Aggregation.sort(Sort.Direction.ASC, "currency")).as("totals")
        .and(context -> new Document("$group", new Document("_id", new Document("month", month).append("currency", "$currency"))
            .append("orderCount", new Document("$sum", 1)).append("totalAmount", new Document("$sum", "$totalAmount"))),
          context -> new Document("$project", new Document("_id", 0).append("month", "$_id.month").append("currency", "$_id.currency").append("orderCount", 1).append("totalAmount", 1)),
          Aggregation.sort(Sort.by("month", "currency"))).as("monthly")
        .and(Aggregation.sort(Sort.by(Sort.Direction.DESC, "transactionTime", "_id")), Aggregation.limit(5),
          context -> new Document("$project", new Document("invoiceNumber", 1).append("distributionPartnerId", 1).append("transactionTime", 1)
              .append("status", 1).append("currency", 1).append("totalAmount", 1))).as("recent")
        .and(context -> new Document("$group", new Document("_id", new Document("tenant", "$tenantId").append("partner", "$distributionPartnerId").append("currency", "$currency"))
            .append("invoiceCount", new Document("$sum", 1)).append("totalAmount", new Document("$sum", "$totalAmount"))),
          context -> new Document("$project", new Document("_id", 0).append("sellerTenantId", "$_id.tenant").append("distributionPartnerId", "$_id.partner")
              .append("currency", "$_id.currency").append("invoiceCount", 1).append("totalAmount", 1)),
          Aggregation.sort(Sort.by("sellerTenantId", "distributionPartnerId", "currency"))).as("partners")
        .and(context -> new Document("$group", new Document("_id", null).append("firstPurchaseAt", new Document("$min", "$transactionTime"))
            .append("lastPurchaseAt", new Document("$max", "$transactionTime")).append("activeMonths", new Document("$addToSet", month)))).as("frequency"));
    var facet = mongo.aggregate(Aggregation.newAggregation(operations), "customer_invoice_link", StatisticsFacet.class).getUniqueMappedResult();
    return summarize(facet == null ? new StatisticsFacet() : facet, timeZone);
  }

  CustomerStatisticsResponse summarize(StatisticsFacet facet, ZoneId zone) {
    var spending = facet.totals.stream().map(row -> new CustomerStatisticsResponse.CurrencySpend(row.getCurrency(), row.getInvoiceCount(), row.getTotalAmount())).toList();
    long count = spending.stream().mapToLong(CustomerStatisticsResponse.CurrencySpend::invoiceCount).sum();
    var frequency = facet.frequency.isEmpty() ? null : facet.frequency.get(0);
    long months = frequency == null || frequency.firstPurchaseAt == null || frequency.lastPurchaseAt == null ? 0
        : ChronoUnit.MONTHS.between(YearMonth.from(frequency.firstPurchaseAt.atZone(zone)), YearMonth.from(frequency.lastPurchaseAt.atZone(zone))) + 1;
    var cadence = new CustomerStatisticsResponse.PurchaseFrequency(frequency == null ? 0 : frequency.activeMonths.size(), months,
        months == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(months), 4, RoundingMode.HALF_UP),
        frequency == null ? null : frequency.firstPurchaseAt, frequency == null ? null : frequency.lastPurchaseAt);
    record Partner(String tenant, String partner) {}
    var partners = new LinkedHashMap<Partner, List<CustomerStatisticsResponse.CurrencySpend>>();
    facet.partners.forEach(row -> partners.computeIfAbsent(new Partner(row.sellerTenantId, row.distributionPartnerId), ignored -> new ArrayList<>())
        .add(new CustomerStatisticsResponse.CurrencySpend(row.getCurrency(), row.getInvoiceCount(), row.getTotalAmount())));
    return new CustomerStatisticsResponse(count, spending, count, spending,
        facet.monthly.stream().map(row -> new CustomerStatisticsResponse.MonthlySpend(row.month, row.currency, row.orderCount, row.totalAmount)).toList(), cadence,
        facet.recent.stream().map(row -> new CustomerStatisticsResponse.RecentPurchase(row.getId(), row.getInvoiceNumber(), row.getDistributionPartnerId(), row.getTransactionTime(),
            row.getStatus().name(), row.getCurrency(), row.getTotalAmount())).toList(),
        partners.entrySet().stream().map(entry -> new CustomerStatisticsResponse.PartnerPurchases(entry.getKey().tenant(), entry.getKey().partner(),
            entry.getValue().stream().mapToLong(CustomerStatisticsResponse.CurrencySpend::invoiceCount).sum(), List.copyOf(entry.getValue()))).toList(),
        zone.getId(), CustomerStatisticsResponse.SpendingPolicy.current());
  }

  List<AggregationOperation> pipeline(CustomerSession scope, CustomerPurchaseFilter filter, String id) {
    Criteria ownership = Criteria.where("customer_identifier").is(scope.customerId())
        .and("customer_tenant_identifier").is(scope.customerTenantId()).and("claim_status").is("CLAIMED").and("deleted").ne(true);
    ownership.and("tenant_identifier").regex("^[a-fA-F0-9]{24}$");
    ownership.and("distribution_partner_identifier").type(2).ne("");
    if (id != null) ownership.and("invoice_identifier").is(id);
    var stages = new ArrayList<AggregationOperation>(); stages.add(Aggregation.match(ownership));
    stages.add(context -> new Document("$addFields", new Document("invoiceObjectId", new Document("$convert",
        new Document("input", "$invoice_identifier").append("to", "objectId").append("onError", null).append("onNull", null)))));
    stages.add(Aggregation.lookup("invoice", "invoiceObjectId", "_id", "invoice"));
    stages.add(Aggregation.unwind("invoice"));
    stages.add(context -> new Document("$match", new Document("$expr", new Document("$and", List.of(
        new Document("$eq", List.of("$invoice.tenantId", "$tenant_identifier")),
        new Document("$eq", List.of("$invoice.distributionPartnerId", "$distribution_partner_identifier")))))));
    stages.add(context -> new Document("$replaceRoot", new Document("newRoot", "$invoice")));
    // Preserve the single-Invoice invariant even if historical/corrupt link duplicates predate the unique index.
    stages.add(context -> new Document("$group", new Document("_id", "$_id").append("invoice", new Document("$first", "$$ROOT"))));
    stages.add(context -> new Document("$replaceRoot", new Document("newRoot", "$invoice")));
    Criteria criteria = new Criteria();
    if (filter.invoiceCode() != null && !filter.invoiceCode().isBlank()) criteria.and("invoiceNumber").regex(Pattern.quote(filter.invoiceCode()), "i");
    if (filter.distributionPartnerId() != null && !filter.distributionPartnerId().isBlank()) criteria.and("distributionPartnerId").is(filter.distributionPartnerId());
    if (filter.status() != null) criteria.and("status").is(filter.status().name());
    if (filter.from() != null || filter.to() != null) {
      Criteria time = criteria.and("transactionTime");
      if (filter.from() != null) time.gte(Date.from(filter.from()));
      if (filter.to() != null) time.lte(Date.from(filter.to()));
    }
    stages.add(Aggregation.match(criteria));
    return stages;
  }

  @Getter @Setter public static class CurrencyAggregate {
    private String currency;
    private long invoiceCount;
    private BigDecimal totalAmount;
  }
  @Getter @Setter public static class StatisticsFacet {
    private List<CurrencyAggregate> totals = List.of();
    private List<MonthAggregate> monthly = List.of();
    private List<Invoice> recent = List.of();
    private List<PartnerAggregate> partners = List.of();
    private List<FrequencyAggregate> frequency = List.of();
  }
  @Getter @Setter public static class MonthAggregate {
    private String month;
    private String currency;
    private long orderCount;
    private BigDecimal totalAmount;
  }
  @Getter @Setter public static class PartnerAggregate extends CurrencyAggregate {
    private String sellerTenantId;
    private String distributionPartnerId;
  }
  @Getter @Setter public static class FrequencyAggregate {
    private Instant firstPurchaseAt;
    private Instant lastPurchaseAt;
    private List<String> activeMonths = List.of();
  }
}
