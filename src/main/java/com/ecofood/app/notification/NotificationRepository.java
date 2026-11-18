package com.ecofood.app.notification;

import com.ecofood.app.notification.NotificationContracts.Channel;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationRepository extends MongoRepository<Notification, ObjectId> {
  // Keep deleted/suppressed events in deduplication; a retry must never re-send them.
  Optional<Notification> findByRecipientCustomerIdAndCustomerTenantIdAndSourceTenantIdAndEventIdAndDeliveryChannel(
      String customerId, String portalTenantId, String sourceTenantId, String eventId, Channel channel);
}
