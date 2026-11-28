package com.ecofood.app.notification.service;

import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRead;
import com.ecofood.app.notification.security.NotificationAccess;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NotificationReadService {
  private final MongoTemplate mongo;
  private final NotificationAccess access;

  public long unreadCount() {
    var scope = access.current(); scope.requireRead();
    // Join on the marker's primary key; convert Mongo ObjectIds to the same String used by the API.
    var lookup = new Document("from", "notification_read")
        .append("let", new Document("readId", new Document("$concat", List.of(
            scope.tenant() + ":" + scope.actor() + ":", new Document("$toString", "$_id")))))
        .append("pipeline", List.of(new Document("$match", new Document("$expr",
            new Document("$eq", List.of("$_id", "$$readId"))))))
        .append("as", "readMarkers");
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("tenantId").is(scope.tenant())),
        context -> new Document("$lookup", lookup),
        Aggregation.match(Criteria.where("readMarkers.0").exists(false)),
        Aggregation.count().as("unreadCount"));
    var result = mongo.aggregate(aggregation, Notification.class, Document.class).getUniqueMappedResult();
    return result == null ? 0 : ((Number) result.get("unreadCount")).longValue();
  }

  public void markRead(String id) {
    var scope = access.current(); scope.requireRead();
    if (!mongo.exists(Query.query(Criteria.where("_id").is(id).and("tenantId").is(scope.tenant())), Notification.class))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
    String key = scope.tenant() + ":" + scope.actor() + ":" + id;
    mongo.upsert(Query.query(Criteria.where("_id").is(key)), new Update()
        .setOnInsert("tenantId", scope.tenant()).setOnInsert("userId", scope.actor())
        .setOnInsert("notificationId", id).setOnInsert("readAt", Instant.now()), NotificationRead.class);
  }
}
