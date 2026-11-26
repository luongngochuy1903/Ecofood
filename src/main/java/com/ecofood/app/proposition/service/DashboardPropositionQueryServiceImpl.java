package com.ecofood.app.proposition.service;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.core.dto.DashboardProposition;
import com.ecofood.core.service.DashboardPropositionQueryService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class DashboardPropositionQueryServiceImpl implements DashboardPropositionQueryService {
  private final MongoTemplate mongo;
  @Override public List<DashboardProposition> find(String tenant, Set<String> ids) {
    if (ids != null && ids.isEmpty()) return List.of();
    Query query = Query.query(Criteria.where("deleted").ne(true));
    query.addCriteria(new Criteria().orOperator(Criteria.where("owningTenantId").is(new ObjectId(tenant)),
        Criteria.where("definition.brandId").is(tenant), Criteria.where("definition.distributionPartnerIds").is(tenant), Criteria.where("plans.tenantIds").is(tenant),
        Criteria.where("actualMetrics.tenantId").is(tenant)));
    if (ids != null) query.addCriteria(Criteria.where("_id").in(ids.stream().map(ObjectId::new).toList()));
    return mongo.find(query, PropositionEntity.class).stream().filter(p -> p.getDefinition() != null)
        .map(p -> new DashboardProposition(p.getIdAsString(), p.getDefinition().name(), p.getDefinition().currency(),
            p.getActualMetrics() == null ? List.of() : p.getActualMetrics().stream().filter(r -> r.tenantId() == null || tenant.equals(r.tenantId()))
                .map(r -> new com.ecofood.core.dto.EconomicMetricsRequest(r.month(), r.year(), r.totalSalesVolume(), r.profit(), r.ingredientCost(),
                    r.platformCost(), r.quarter(), r.revenue(), tenant, r.currency() == null ? p.getDefinition().currency() : r.currency())).toList())).toList();
  }
}
