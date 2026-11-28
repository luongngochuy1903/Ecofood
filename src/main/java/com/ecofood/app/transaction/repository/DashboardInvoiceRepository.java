package com.ecofood.app.transaction.repository;

import com.ecofood.app.transaction.entity.Invoice;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DashboardInvoiceRepository {
  private final MongoTemplate mongo;

  public Page<Invoice> find(Map<String, Set<String>> scopes, Pageable page) {
    List<Criteria> allowed = new ArrayList<>();
    scopes.forEach((tenant, ids) -> {
      if (ids == null) allowed.add(Criteria.where("tenantId").is(tenant));
      else if (!ids.isEmpty()) allowed.add(Criteria.where("tenantId").is(tenant).and("id").in(ids));
    });
    if (allowed.isEmpty()) return Page.empty(page);
    Criteria criteria = new Criteria().orOperator(allowed);
    var rows = mongo.find(Query.query(criteria).with(page), Invoice.class);
    return PageableExecutionUtils.getPage(rows, page, () -> mongo.count(Query.query(criteria), Invoice.class));
  }
}
