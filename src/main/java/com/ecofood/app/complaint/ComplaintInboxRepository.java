package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.complaint.ComplaintProcessingContracts.Search;
import com.ecofood.app.transaction.entity.Invoice;
import java.util.*;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Scope-first join with the true Invoice; no denormalized invoice snapshot or unscoped enumeration. */
@Repository
public class ComplaintInboxRepository {
  private final ComplaintMongoStore store;
  private final ComplaintRepository complaints;
  public ComplaintInboxRepository(ComplaintMongoStore store, ComplaintRepository complaints) { this.store = store; this.complaints = complaints; }
  public record Row(Complaint complaint, String invoiceCode) {}
  public Page<Row> query(InboxScope scope, Filter filter, Search search, Pageable page) {
    ComplaintRepository.validatePage(page);
    var query = complaints.scoped(scope);
    if (filter != null) {
      ComplaintValidation.require(filter.from() == null || filter.to() == null || !filter.from().isAfter(filter.to()), "Invalid date range");
      if (!filter.kinds().isEmpty()) query.addCriteria(Criteria.where("kind").in(filter.kinds()));
      if (!filter.statuses().isEmpty()) query.addCriteria(Criteria.where("status").in(filter.statuses()));
      if (filter.from() != null || filter.to() != null) { var dates = Criteria.where("createdAt"); if (filter.from() != null) dates.gte(filter.from()); if (filter.to() != null) dates.lte(filter.to()); query.addCriteria(dates); }
    }
    if (search != null) {
      if (text(search.complaintCode())) query.addCriteria(Criteria.where("complaintCode").regex(literal(search.complaintCode())));
      if (text(search.categoryCode())) { ComplaintValidation.require(search.categoryCode().matches("[A-Z][A-Z0-9_]{0,63}"), "Invalid category"); query.addCriteria(Criteria.where("categoryCode").is(search.categoryCode())); }
      if (text(search.queueId())) { ComplaintValidation.code(search.queueId()); query.addCriteria(Criteria.where("currentQueueId").is(search.queueId())); }
    }
    var mongo = store.template(); var entity = mongo.getConverter().getMappingContext().getPersistentEntity(Complaint.class);
    var match = new QueryMapper(mongo.getConverter()).getMappedObject(query.getQueryObject(), entity);
    var variables = new Document("id", new Document("$convert", new Document("input", "$invoiceId").append("to", "objectId").append("onError", null).append("onNull", null)))
        .append("tenant", "$invoiceTenantId").append("partner", "$distributionPartnerId");
    var equalities = List.of(new Document("$eq", List.of("$_id", "$$id")), new Document("$eq", List.of("$tenantId", "$$tenant")), new Document("$eq", List.of("$distributionPartnerId", "$$partner")));
    var invoiceMatch = new Document("$match", new Document("$expr", new Document("$and", equalities)));
    var lookup = new Document("from", store.collectionName(Invoice.class)).append("let", variables)
        .append("pipeline", List.of(invoiceMatch, new Document("$project", new Document("invoiceNumber", 1)))).append("as", "__invoice");
    var stages = new ArrayList<Document>(); stages.add(new Document("$match", match)); stages.add(new Document("$lookup", lookup));
    if (search != null && text(search.invoiceCode())) stages.add(new Document("$match", new Document("__invoice.invoiceNumber", literal(search.invoiceCode()))));
    stages.add(new Document("$set", new Document("__invoiceCode", new Document("$arrayElemAt", List.of("$__invoice.invoiceNumber", 0)))));
    stages.add(new Document("$unset", "__invoice"));
    var sort = new Document(); var orders = page.getSort().isSorted() ? page.getSort() : Sort.by(Sort.Direction.DESC, "createdAt");
    for (var order : orders) sort.put(entity.getPersistentProperty(order.getProperty()).getFieldName(), order.isAscending() ? 1 : -1);
    sort.put("_id", -1);
    stages.add(new Document("$facet", new Document("total", List.of(new Document("$count", "count"))).append("rows", List.of(new Document("$sort", sort), new Document("$skip", page.getOffset()), new Document("$limit", page.getPageSize())))));
    var operations = stages.stream().<AggregationOperation>map(stage -> context -> stage).toList();
    var result = mongo.aggregate(Aggregation.newAggregation(operations), mongo.getCollectionName(Complaint.class), Document.class).getUniqueMappedResult();
    if (result == null) return Page.empty(page);
    var counts = result.getList("total", Document.class); long count = counts.isEmpty() ? 0 : ((Number) counts.get(0).get("count")).longValue();
    var rows = result.getList("rows", Document.class).stream().map(doc -> { String code = doc.getString("__invoiceCode"); doc.remove("__invoiceCode"); return new Row(mongo.getConverter().read(Complaint.class, doc), code); }).toList();
    return new PageImpl<>(rows, page, count);
  }
  private boolean text(String value) { return value != null && !value.isBlank(); }
  private Pattern literal(String value) { ComplaintValidation.require(value.length() <= 128, "Search too long"); return Pattern.compile(Pattern.quote(value.strip()), Pattern.CASE_INSENSITIVE); }
}
