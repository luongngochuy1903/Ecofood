package com.ecofood.app.product.service;

import com.ecofood.app.product.entity.ProductEntity;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.core.enums.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Idempotent cascade; queued on the Product so partial/network failures can be retried. */
@Service @RequiredArgsConstructor @Slf4j
public class ProductVersionReviewService {
  private final ProductProcessStore processes;
  private final MongoTemplate mongo;

  public void apply(ProductEntity product, int version) {
    var source = processes.findByProductIdAndVersion(product.getId(), version)
        .orElseThrow(() -> new IllegalStateException("Product source version is missing"));
    if (source.getStatus() != ProductStatusEnum.PENDING_REVIEW) {
      source.setStatus(ProductStatusEnum.PENDING_REVIEW);
      source.setApprovedAt(null); source.setApprovedBy(null);
      processes.save(source);
    }
    var query = new Query(Criteria.where("enterpriseId").is(product.getEnterpriseId())
        .and("productId").is(product.getId()).and("productVersion").is(version)
        .and("deleted").ne(true));
    query.addCriteria(new Criteria().orOperator(Criteria.where("status").ne(PropositionStatusEnum.PENDING_REVIEW), Criteria.where("sourceProcess.status").ne(ProductStatusEnum.PENDING_REVIEW)));
    var now = Instant.now();
    var actor = "PRODUCT_VERSION_REVIEW";
    var audit = new org.bson.Document("action", "PRODUCT_VERSION_PENDING_REVIEW")
        .append("actorId", actor).append("at", now).append("status", "PENDING_REVIEW");
    mongo.updateMulti(query, new Update().set("status", PropositionStatusEnum.PENDING_REVIEW)
        .unset("approvedAt").unset("approvedBy").set("sourceProcess.status", ProductStatusEnum.PENDING_REVIEW)
        // This system action has no user ObjectId; its identity lives in changes.actorId.
        .set("updatedAt", now).unset("updatedBy").inc("revision", 1).push("changes", audit), PropositionEntity.class);
    // Ack only after both steps succeed. Revision prevents a stale Product save restoring the queue.
    mongo.updateFirst(new Query(Criteria.where("_id").is(product.getId()).and("pendingVersionReviews").is(version)),
        new Update().pull("pendingVersionReviews", version).inc("revision", 1), ProductEntity.class);
  }

  @Scheduled(fixedDelayString="${ecofood.products.review-retry-ms:10000}", initialDelayString="${ecofood.products.review-initial-delay-ms:15000}")
  public void retry() {
    for (var product : mongo.find(new Query(Criteria.where("pendingVersionReviews.0").exists(true)).limit(50), ProductEntity.class)) {
      for (int version : product.getPendingVersionReviews()) try { apply(product, version); }
      catch (RuntimeException ex) { log.warn("Product version review cascade deferred for {} v{} ({})", product.getIdAsString(), version, ex.getClass().getSimpleName()); }
    }
  }
}
