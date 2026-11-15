package com.ecofood.app.feedback.repository;

import com.ecofood.app.feedback.entity.ServiceFeedback;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ServiceFeedbackRepository extends MongoRepository<ServiceFeedback, ObjectId> {
  // Deliberately include soft-deleted records: deletion must not reopen an Invoice for feedback.
  boolean existsByTenantIdAndInvoiceId(String tenantId, String invoiceId);
}
