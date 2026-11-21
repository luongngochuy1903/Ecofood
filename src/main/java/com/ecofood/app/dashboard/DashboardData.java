package com.ecofood.app.dashboard;

import com.ecofood.app.metrics.entity.ActualMetrics;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.service.PropositionAccess;
import com.ecofood.core.enums.PartnerTypeEnum;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Read-only projection access. Never uses Proposition.actualMetrics or live Product Costing. */
@Service
@RequiredArgsConstructor
public class DashboardData {
  private final MongoTemplate mongo;
  private final PropositionAccess propositionAccess;
  public List<PropositionEntity> propositions(DashboardAccess.Actor actor) {
    Set<String> read = new HashSet<>(actor.tenantScope());
    if (actor.tenant().relationships() != null) actor.tenant().relationships().stream()
        .filter(r -> "MT".equals(r.getRelationshipTypeCode())).map(r -> r.getToPartyId()).filter(Objects::nonNull).forEach(read::add);
    boolean enterprise = actor.tenant().partnerType() == PartnerTypeEnum.ENTERPRISE || actor.tenant().partnerType() == PartnerTypeEnum.REGIONAL_ENTERPRISE;
    List<Criteria> scope = new ArrayList<>(List.of(Criteria.where("definition.distributionPartnerIds").in(read),
        Criteria.where("definition.brandId").in(read), Criteria.where("plans.tenantIds").in(actor.tenantScope())));
    if (enterprise && actor.tenant().enterpriseId() != null && org.bson.types.ObjectId.isValid(actor.tenant().enterpriseId()))
      scope.add(Criteria.where("enterpriseId").is(new org.bson.types.ObjectId(actor.tenant().enterpriseId())));
    Query query = Query.query(new Criteria().andOperator(Criteria.where("deleted").ne(true), new Criteria().orOperator(scope.toArray(Criteria[]::new))));
    var libraryActor = new PropositionAccess.Actor(actor.tenant(), actor.tenantScope(), Set.copyOf(read), enterprise, actor.tenant().partnerType() == PartnerTypeEnum.ECO);
    return mongo.find(query, PropositionEntity.class).stream()
        .filter(p -> p.getDefinition() != null && (!enterprise || p.getEnterpriseId() != null))
        .filter(p -> actor.productIds() == null || (p.getProductId() != null && actor.productIds().contains(p.getProductId().toHexString())))
        .filter(p -> propositionAccess.canRead(libraryActor, p)).toList();
  }
  public List<ActualMetrics> actual(Set<String> tenantIds, Set<String> propositions, Integer year, String currency) {
    if (tenantIds.isEmpty() || propositions.isEmpty()) return List.of();
    Query query = Query.query(Criteria.where("tenantId").in(tenantIds).and("propositionId").in(propositions));
    if (year != null) query.addCriteria(Criteria.where("year").is(year));
    if (currency != null) query.addCriteria(Criteria.where("currency").is(currency));
    var rows = mongo.find(query.limit(50001), ActualMetrics.class);
    if (rows.size() > 50000) throw DashboardAccess.error(HttpStatus.UNPROCESSABLE_ENTITY, "Narrow the Dashboard filter; projection row limit exceeded");
    return rows;
  }
}
