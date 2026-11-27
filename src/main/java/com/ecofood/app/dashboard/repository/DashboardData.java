package com.ecofood.app.dashboard.repository;

import com.ecofood.app.dashboard.security.DashboardAccess;
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

/** Reads actuals embedded in Propositions; never queries a separate metrics collection. */
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
    // Dashboard keeps the pre-existing single-enterprise scope here — only the
    // Product/Proposition directory reads (PropositionAccess.current()) widen
    // to owned REGIONAL_ENTERPRISE children; the Mongo query above already
    // independently filters by the caller's own enterpriseId.
    Set<String> enterpriseRead = enterprise && actor.tenant().enterpriseId() != null
        ? Set.of(actor.tenant().enterpriseId()) : Set.of();
    var libraryActor = new PropositionAccess.Actor(actor.tenant(), actor.tenantScope(), Set.copyOf(read), enterprise,
        actor.tenant().partnerType() == PartnerTypeEnum.ECO, enterpriseRead);
    return mongo.find(query, PropositionEntity.class).stream()
        .filter(p -> p.getDefinition() != null && (!enterprise || p.getEnterpriseId() != null))
        .filter(p -> actor.productIds() == null || (p.getProductId() != null && actor.productIds().contains(p.getProductId().toHexString())))
        .filter(p -> propositionAccess.canRead(libraryActor, p)).toList();
  }
  public List<ActualMetrics> actual(Set<String> tenantIds, Set<String> propositions, Integer year, String currency) {
    if (tenantIds.isEmpty() || propositions.isEmpty()) return List.of();
    Query query = Query.query(Criteria.where("_id").in(propositions.stream()
        .map(org.bson.types.ObjectId::new).toList()).and("deleted").ne(true));
    var rows = new ArrayList<ActualMetrics>();
    for (var p : mongo.find(query, PropositionEntity.class)) {
      if (p.getActualMetrics() == null) continue;
      for (var row : p.getActualMetrics()) {
        // Legacy totals without tenant/month/currency cannot be assigned to an individual partner.
        if (row.tenantId() == null || row.currency() == null || row.month() == null
            || row.year() == null || !tenantIds.contains(row.tenantId())
            || year != null && !year.equals(row.year())
            || currency != null && !currency.equals(row.currency())) continue;
        long units = row.totalSalesVolume() == null ? 0 : row.totalSalesVolume().longValueExact();
        String key = p.getIdAsString() + "/" + row.tenantId() + "/" + row.year()
            + "/" + row.month() + "/" + row.currency();
        var zero = java.math.BigDecimal.ZERO;
        rows.add(new ActualMetrics(key, p.getIdAsString(), row.tenantId(), row.year(), row.month(),
            row.currency(), units, row.ingredientCost(), row.platformCost(), row.profit(),
            new ActualMetrics.KnownTotals(row.ingredientCost() == null ? zero : row.ingredientCost(),
                row.platformCost() == null ? zero : row.platformCost(), row.profit() == null ? zero : row.profit()),
            new ActualMetrics.Coverage(row.ingredientCost() == null ? 0 : units,
                row.platformCost() == null ? 0 : units, row.profit() == null ? 0 : units,
                row.ingredientCost() == null || row.platformCost() == null || row.profit() == null ? units : 0),
            "PROPOSITION_ACTUAL_V1", p.getRevision() == null ? 0 : p.getRevision(), p.getUpdatedAt()));
      }
    }
    if (rows.size() > 50000) throw DashboardAccess.error(HttpStatus.UNPROCESSABLE_ENTITY, "Narrow the Dashboard filter; projection row limit exceeded");
    return rows;
  }
}
