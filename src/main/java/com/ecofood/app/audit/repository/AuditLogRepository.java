package com.ecofood.app.audit.repository;

import com.ecofood.app.audit.entity.AuditLogEntity;
import com.ecofood.core.dto.AuditLogDtos.Filter;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuditLogRepository {
  private final MongoTemplate mongo;

  public void insert(AuditLogEntity event) { mongo.insert(event); }

  public AuditLogEntity detail(String tenant, String id) {
    return mongo.findOne(Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(id)), AuditLogEntity.class);
  }

  public List<AuditLogEntity> list(String tenant, Filter f) {
    Query query = Query.query(Criteria.where("tenantId").is(tenant));
    if (f.actorId() != null) query.addCriteria(Criteria.where("actorId").is(f.actorId()));
    if (f.action() != null) query.addCriteria(Criteria.where("action").is(f.action()));
    if (f.module() != null) query.addCriteria(Criteria.where("controller").is(f.module() + "Controller"));
    if (f.outcome() != null) query.addCriteria(Criteria.where("outcome").is(f.outcome()));
    if (f.from() != null || f.to() != null) {
      Criteria time = Criteria.where("timestamp");
      if (f.from() != null) time.gte(f.from());
      if (f.to() != null) time.lt(f.to());
      query.addCriteria(time);
    }
    query.with(Sort.by(Sort.Direction.DESC, "timestamp", "_id"))
        .skip((long) f.page() * f.size()).limit(f.size() + 1);
    return mongo.find(query, AuditLogEntity.class);
  }

  public List<String> actors(String tenant, String prefix) {
    Criteria scope = Criteria.where("tenantId").is(tenant);
    if (!prefix.isEmpty()) scope.and("actorId").regex("^" + Pattern.quote(prefix));
    var pipeline = Aggregation.newAggregation(Aggregation.match(scope), Aggregation.group("actorId"),
        Aggregation.sort(Sort.Direction.ASC, "_id"), Aggregation.limit(20));
    return mongo.aggregate(pipeline, AuditLogEntity.class, Document.class).getMappedResults().stream()
        .map(row -> row.getString("_id")).toList();
  }
}
