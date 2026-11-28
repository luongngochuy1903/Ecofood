package com.ecofood.app.proposition.config;

import com.ecofood.app.proposition.entity.PropositionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Idempotent: Plans no longer carry their own start/end dates; only the Proposition's period gates sales. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanDatesMigration implements ApplicationRunner {
  private final MongoTemplate mongo;

  @Override
  public void run(ApplicationArguments args) {
    var legacy = Query.query(new Criteria().orOperator(
        Criteria.where("plans.startDate").exists(true), Criteria.where("plans.endDate").exists(true)));
    var result = mongo.updateMulti(legacy,
        new Update().unset("plans.$[].startDate").unset("plans.$[].endDate"), PropositionEntity.class);
    if (result.getModifiedCount() > 0) {
      log.info("Removed Plan start/end dates from {} propositions", result.getModifiedCount());
    }
  }
}
