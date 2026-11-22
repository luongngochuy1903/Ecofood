package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.notification.config.NotificationMongoConfig;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRule;
import com.ecofood.app.submission.entity.Submission;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;

class NotificationIndexTest {
  @Test void oneDeliveryPerEventRuleRecipientChannelAndSeparateConsumerIndex() throws Exception {
    var mongo = mock(MongoTemplate.class); var notices = mock(IndexOperations.class); var rules = mock(IndexOperations.class); var events = mock(IndexOperations.class);
    when(mongo.indexOps(Notification.class)).thenReturn(notices); when(mongo.indexOps(NotificationRule.class)).thenReturn(rules); when(mongo.indexOps(Submission.class)).thenReturn(events);
    new NotificationMongoConfig().notificationIndexes(mongo).run(null);
    var captor = ArgumentCaptor.forClass(IndexDefinition.class); verify(notices, times(3)).createIndex(captor.capture());
    var unique = captor.getAllValues().stream().filter(i -> Boolean.TRUE.equals(i.getIndexOptions().getBoolean("unique"))).findFirst().orElseThrow();
    assertEquals(List.of("submissionId", "ruleId", "sendTo", "channel"), List.copyOf(unique.getIndexKeys().keySet()));
    var eventIndex = ArgumentCaptor.forClass(IndexDefinition.class); verify(events).createIndex(eventIndex.capture());
    assertTrue(eventIndex.getValue().getIndexKeys().containsKey("notification.state")); assertFalse(eventIndex.getValue().getIndexKeys().containsKey("actualMetrics.state"));
  }
}
