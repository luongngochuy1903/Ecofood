package com.ecofood.app.catalog.config;

import com.ecofood.app.catalog.entity.ApiPublication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class CatalogMongoConfig {
  @Bean public ApplicationRunner catalogPublicationIndexes(MongoTemplate mongo) {
    return args->{
      mongo.indexOps(ApiPublication.class).createIndex(new Index().on("tenantId",Direction.ASC).on("catalogType",Direction.ASC).on("apiKey",Direction.ASC).on("version",Direction.ASC).unique().named("ux_publication_identity"));
      mongo.indexOps(ApiPublication.class).createIndex(new Index().on("tenantId",Direction.ASC).on("createdAt",Direction.DESC).named("ix_publication_tenant"));
    };
  }
}
