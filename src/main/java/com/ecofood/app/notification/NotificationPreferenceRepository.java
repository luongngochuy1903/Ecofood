package com.ecofood.app.notification;

import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationPreferenceRepository extends MongoRepository<NotificationPreference, ObjectId> {
  Optional<NotificationPreference> findByCustomerIdAndCustomerTenantIdAndDeletedNot(String customerId, String portalTenantId, Boolean deleted);
}
