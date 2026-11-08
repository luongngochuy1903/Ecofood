package com.ecofood.app.transaction.repository;

import com.ecofood.app.transaction.dto.TransactionFilter;
import com.ecofood.app.transaction.dto.TransactionSummaryResponse.CurrencyTotal;
import com.ecofood.app.transaction.entity.Invoice;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InvoiceRepositoryImpl implements InvoiceRepositoryCustom {
  private final MongoTemplate mongoTemplate;

  @Override
  public Page<Invoice> findTransactions(List<String> tenantIds, boolean unrestricted,
      TransactionFilter filter, Pageable pageable) {
    Query query = new Query(criteria(tenantIds, unrestricted, filter));
    long count = mongoTemplate.count(query, Invoice.class);
    query.with(pageable);
    // List rows do not need to load embedded invoice items.
    query.fields().exclude("items");
    return new PageImpl<>(mongoTemplate.find(query, Invoice.class), pageable, count);
  }

  @Override
  public Optional<Invoice> findScopedInvoice(String id, List<String> tenantIds, boolean unrestricted) {
    var filter = new TransactionFilter(id, null, null, null, null);
    return Optional.ofNullable(mongoTemplate.findOne(
        new Query(criteria(tenantIds, unrestricted, filter)), Invoice.class));
  }

  @Override
  public List<CurrencyTotal> summarizeTransactions(List<String> tenantIds, boolean unrestricted,
      TransactionFilter filter) {
    var aggregation = Aggregation.newAggregation(Invoice.class,
        Aggregation.match(criteria(tenantIds, unrestricted, filter)),
        Aggregation.group("currency").count().as("totalTransactions")
            .sum("totalAmount").as("totalAmount"),
        Aggregation.project("totalTransactions", "totalAmount").and("_id").as("currency")
            .andExclude("_id"),
        Aggregation.sort(Sort.Direction.ASC, "currency"));
    return mongoTemplate.aggregate(aggregation, CurrencyTotal.class).getMappedResults();
  }

  private Criteria criteria(List<String> tenantIds, boolean unrestricted, TransactionFilter filter) {
    Criteria criteria = new Criteria();
    // An empty permitted scope must match nothing, never become unrestricted.
    if (!unrestricted) criteria.and("tenantId").in(tenantIds);
    if (filter.invoiceId() != null) criteria.and("_id").is(new ObjectId(filter.invoiceId()));
    if (filter.invoiceNumber() != null && !filter.invoiceNumber().isBlank())
      criteria.and("invoiceNumber").regex(Pattern.quote(filter.invoiceNumber()), "i");
    if (filter.status() != null) criteria.and("status").is(filter.status());
    if (filter.from() != null || filter.to() != null) {
      Criteria time = criteria.and("transactionTime");
      if (filter.from() != null) time.gte(filter.from());
      if (filter.to() != null) time.lte(filter.to());
    }
    return criteria;
  }
}
