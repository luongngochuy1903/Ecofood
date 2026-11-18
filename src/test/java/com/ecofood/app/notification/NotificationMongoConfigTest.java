package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;

class NotificationMongoConfigTest {
  @Test void uniqueIndexesIncludeRecipientPortalSourceEventAndChannelAndDoNotReopenDeletedEvents() throws Exception {
    var mongo = mock(MongoTemplate.class); var notes = mock(IndexOperations.class); var preferences = mock(IndexOperations.class);
    when(mongo.indexOps(Notification.class)).thenReturn(notes); when(mongo.indexOps(NotificationPreference.class)).thenReturn(preferences);
    new NotificationMongoConfig().notificationIndexes(mongo).run(null);
    var index = ArgumentCaptor.forClass(IndexDefinition.class); verify(notes, times(2)).createIndex(index.capture());
    var unique = index.getAllValues().get(0);
    assertEquals(true, unique.getIndexOptions().get("unique"));
    assertEquals(java.util.Set.of("customer_tenant_identifier", "recipient_customer_identifier", "source_tenant_identifier", "event_id", "delivery_channel"), unique.getIndexKeys().keySet());
    assertFalse(unique.getIndexKeys().containsKey("deleted"));
    var preference = ArgumentCaptor.forClass(IndexDefinition.class); verify(preferences).createIndex(preference.capture());
    assertEquals(true, preference.getValue().getIndexOptions().get("unique"));
    assertNotNull(NotificationPreference.class.getDeclaredField("version").getAnnotation(org.springframework.data.annotation.Version.class));
  }
}
