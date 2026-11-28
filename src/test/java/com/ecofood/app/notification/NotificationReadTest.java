package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRead;
import com.ecofood.app.notification.security.NotificationAccess;
import com.ecofood.app.notification.service.NotificationReadService;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.web.server.ResponseStatusException;

class NotificationReadTest {
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final NotificationAccess access = mock(NotificationAccess.class);
  private final NotificationReadService service = new NotificationReadService(mongo, access);

  private void allowed() { when(access.current()).thenReturn(new NotificationAccess.Scope("tenant", "user", false, true, false)); }

  @Test void countsAllUnreadWithTenantAndUserIsolationRatherThanOnlyTheRecentPage() {
    allowed();
    when(mongo.aggregate(any(Aggregation.class),eq(Notification.class),eq(Document.class)))
        .thenReturn(new AggregationResults<>(List.of(new Document("unreadCount",120)),new Document()));
    assertEquals(120,service.unreadCount());
    var query = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(query.capture(),eq(Notification.class),eq(Document.class));
    var pipeline = query.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
    assertEquals(new Document("$match",new Document("tenantId","tenant")),pipeline.get(0));
    var lookup = pipeline.get(1).get("$lookup",Document.class);
    assertEquals("notification_read",lookup.getString("from"));
    assertEquals(List.of("tenant:user:",new Document("$toString","$_id")),
        lookup.get("let",Document.class).get("readId",Document.class).get("$concat"));
    assertEquals(new Document("$match",new Document("readMarkers.0",new Document("$exists",false))),pipeline.get(2));
    assertTrue(pipeline.stream().noneMatch(stage -> stage.containsKey("$limit")));
  }
  @Test void noUnreadNotificationsReturnsZero() {
    allowed();
    when(mongo.aggregate(any(Aggregation.class),eq(Notification.class),eq(Document.class)))
        .thenReturn(new AggregationResults<>(List.of(),new Document()));
    assertEquals(0,service.unreadCount());
  }
  @Test void readingTwiceUsesTheSameUserMarkerAndDoesNotTouchDeliveryState() {
    allowed(); when(mongo.exists(any(Query.class),eq(Notification.class))).thenReturn(true);
    service.markRead("notice"); service.markRead("notice");
    verify(mongo,times(2)).exists(argThat(query -> query.getQueryObject().equals(new Document("_id","notice").append("tenantId","tenant"))),eq(Notification.class));
    verify(mongo,times(2)).upsert(argThat(query -> "tenant:user:notice".equals(query.getQueryObject().get("_id"))),
        argThat((Update update) -> {
          var values = update.getUpdateObject().get("$setOnInsert",Document.class);
          return update.getUpdateObject().size() == 1 && "tenant".equals(values.get("tenantId"))
              && "user".equals(values.get("userId")) && "notice".equals(values.get("notificationId")) && values.get("readAt") != null;
        }),eq(NotificationRead.class));
    verifyNoMoreInteractions(mongo);
  }
  @Test void missingOrForeignTenantNotificationCannotBeMarkedRead() {
    allowed();
    assertThrows(ResponseStatusException.class,() -> service.markRead("foreign"));
    verify(mongo,never()).upsert(any(Query.class),any(Update.class),eq(NotificationRead.class));
  }
  @Test void deniedReadPermissionDoesNotQueryOrWriteAnything() {
    when(access.current()).thenReturn(new NotificationAccess.Scope("tenant","user",false,false,false));
    assertThrows(ResponseStatusException.class,service::unreadCount);
    assertThrows(ResponseStatusException.class,() -> service.markRead("notice"));
    verifyNoInteractions(mongo);
  }
}
