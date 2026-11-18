package com.ecofood.app.customer.repository;

import com.ecofood.app.customer.entity.CustomerInvoiceSnapshot;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CustomerInvoiceSnapshotRepository extends MongoRepository<CustomerInvoiceSnapshot, ObjectId> {
  Page<CustomerInvoiceSnapshot> findByTenantIdAndCustomerUserIdAndDeletedNot(
      ObjectId tenantId, ObjectId customerUserId, Boolean deleted, Pageable pageable);

  Optional<CustomerInvoiceSnapshot> findByIdAndTenantIdAndCustomerUserIdAndDeletedNot(
      ObjectId id, ObjectId tenantId, ObjectId customerUserId, Boolean deleted);
}
