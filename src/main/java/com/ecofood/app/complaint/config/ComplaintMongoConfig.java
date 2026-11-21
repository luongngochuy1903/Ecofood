package com.ecofood.app.complaint.config;

import com.ecofood.app.complaint.entity.Complaint;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class ComplaintMongoConfig {
  @Bean ApplicationRunner complaintIndexes(MongoTemplate mongo) {
    return args->{
      mongo.indexOps(Complaint.class).createIndex(new Index().on("customerId",Direction.ASC).on("customerTenantId",Direction.ASC).on("updatedAt",Direction.DESC).named("ix_complaint_customer"));
      mongo.indexOps(Complaint.class).createIndex(new Index().on("queue.tenantId",Direction.ASC).on("complaintType",Direction.ASC).on("status",Direction.ASC).on("updatedAt",Direction.DESC).named("ix_complaint_queue"));
      mongo.indexOps(Complaint.class).createIndex(new Index().on("handoffs.pending",Direction.ASC).named("ix_complaint_handoff"));
    };
  }
}
