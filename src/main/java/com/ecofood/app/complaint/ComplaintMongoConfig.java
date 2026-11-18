package com.ecofood.app.complaint;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class ComplaintMongoConfig {
  @Bean public ApplicationRunner complaintCoreIndexes(ComplaintMongoStore store) {
    return args -> {
      var mongo = store.template();
      mongo.indexOps(Complaint.class).createIndex(new Index().on("complaintCode", Sort.Direction.ASC).unique().named("ux_complaint_code"));
      mongo.indexOps(Complaint.class).createIndex(new Index().on("submissionKey", Sort.Direction.ASC).unique().named("ux_complaint_submission"));
      mongo.indexOps(Complaint.class).createIndex(new Index().on("processingBoundary", Sort.Direction.ASC).on("processingTenantId", Sort.Direction.ASC)
          .on("currentQueueId", Sort.Direction.ASC).on("status", Sort.Direction.ASC).on("created_time_stamp", Sort.Direction.DESC).named("ix_complaint_processing"));
      mongo.indexOps(Complaint.class).createIndex(new Index().on("reporterType", Sort.Direction.ASC).on("reporterUserId", Sort.Direction.ASC)
          .on("reporterHomeTenantId", Sort.Direction.ASC).on("created_time_stamp", Sort.Direction.DESC).named("ix_complaint_reporter"));
      mongo.indexOps(Complaint.class).createIndex(new Index().on("invoiceTenantId", Sort.Direction.ASC).on("invoiceId", Sort.Direction.ASC).named("ix_complaint_invoice"));
      mongo.indexOps(Complaint.class).createIndex(new Index().on("nextProjectionAt", Sort.Direction.ASC).on("projectionLeaseUntil", Sort.Direction.ASC).named("ix_complaint_projection"));
      mongo.indexOps(ComplaintEvent.class).createIndex(new Index().on("complaintId", Sort.Direction.ASC).on("sequence", Sort.Direction.ASC).unique().named("ux_complaint_event_sequence"));
      mongo.indexOps(ComplaintEvent.class).createIndex(new Index().on("commandKey", Sort.Direction.ASC).unique().named("ux_complaint_command"));
      mongo.indexOps(ComplaintEvent.class).createIndex(new Index().on("complaintId", Sort.Direction.ASC).on("visibility", Sort.Direction.ASC).on("sequence", Sort.Direction.ASC).named("ix_complaint_public_history"));
      mongo.indexOps(ComplaintOutbox.class).createIndex(new Index().on("state", Sort.Direction.ASC).on("nextAttemptAt", Sort.Direction.ASC).on("leaseUntil", Sort.Direction.ASC).named("ix_complaint_outbox_due"));
    };
  }
  @Bean public ApplicationRunner complaintIntakeIndexes(ComplaintMongoStore store) {
    return args -> store.template().indexOps("complaint_intake_rate").createIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(java.time.Duration.ZERO).named("ttl_complaint_intake_rate"));
  }
}
