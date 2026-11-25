package com.ecofood.app.proposition.config;

import com.ecofood.app.metrics.service.MongoMetricsTransactions;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.service.PropositionSalesMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** One-time, retry-safe backfill from invoices; never deletes the legacy metrics collection. */
@Component
@RequiredArgsConstructor
public class PropositionActualMetricsMigration implements ApplicationRunner {
  private final MongoTemplate mongo;
  private final MongoMetricsTransactions transactions;
  private final PropositionSalesMetrics metrics;

  @Override
  public void run(ApplicationArguments args) {
    var query = Query.query(Criteria.where("deleted").ne(true)
        .and("actualMetricsVersion").ne(PropositionSalesMetrics.VERSION));
    for (var source : mongo.find(query, PropositionEntity.class)) {
      transactions.execute(ops -> {
        var p = ops.findById(source.getId(), PropositionEntity.class);
        if (p != null && !Boolean.TRUE.equals(p.getDeleted())
            && !Integer.valueOf(PropositionSalesMetrics.VERSION).equals(p.getActualMetricsVersion())) {
          metrics.rebuild(ops, p);
          ops.save(p);
        }
        return null;
      });
    }
  }
}
