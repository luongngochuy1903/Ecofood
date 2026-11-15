package com.ecofood.app.product.service;

import com.ecofood.app.product.entity.ProductEntity;
import com.ecofood.app.product.entity.ProductProcessEntity;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.stereotype.Component;

/** Code uniqueness is enforced by MongoDB even when requests race. Legacy uncoded rows are excluded. */
@Component
@RequiredArgsConstructor
public class ProductIndexes implements InitializingBean {
  private final MongoTemplate mongo;

  @Override public void afterPropertiesSet() {
    mongo.indexOps(ProductEntity.class).ensureIndex(new Index()
        .on("enterprise_identifier", Sort.Direction.ASC).on("product_code", Sort.Direction.ASC)
        .unique().named("enterprise_product_code_unique")
        .partial(PartialIndexFilter.of(new Document("product_code", new Document("$type", "string")))));
    mongo.indexOps(ProductProcessEntity.class).ensureIndex(new Index()
        .on("product_identifier",Sort.Direction.ASC).on("version",Sort.Direction.ASC)
        .unique().named("product_process_version_unique"));
  }
}
