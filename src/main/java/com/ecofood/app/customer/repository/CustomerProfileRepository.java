package com.ecofood.app.customer.repository;

import com.ecofood.app.customer.entity.CustomerProfileEntity;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CustomerProfileRepository extends MongoRepository<CustomerProfileEntity, ObjectId> {
  Optional<CustomerProfileEntity> findByTenantIdAndUserIdAndDeletedNot(
      ObjectId tenantId, ObjectId userId, Boolean deleted);
}
