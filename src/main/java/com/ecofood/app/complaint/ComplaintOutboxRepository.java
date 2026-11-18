package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class ComplaintOutboxRepository {
  private final MongoTemplate mongo;
  private final Clock clock;
  public ComplaintOutboxRepository(ComplaintMongoStore store, Clock clock) { mongo = store.template(); this.clock = clock; }
  public Optional<DomainEvent> findEvent(String eventId) {
    return Optional.ofNullable(mongo.findById(eventId, ComplaintOutbox.class)).map(ComplaintOutbox::getEvent);
  }
  public void append(DomainEvent event) {
    var row = new ComplaintOutbox(); row.setId(event.eventId()); row.setEvent(event); row.setNextAttemptAt(event.occurredAt());
    try { mongo.insert(row); }
    catch (DuplicateKeyException ex) {
      var winner = mongo.findById(event.eventId(), ComplaintOutbox.class);
      if (winner == null || !event.equals(winner.getEvent())) throw new IllegalStateException("Immutable Complaint outbox conflict");
    }
  }
  /** Single logical consumer stream, at-least-once delivery; downstream must deduplicate eventId. */
  public Optional<OutboxLease> claimNext(String consumerId) {
    ComplaintCommandIdentity.requestId(consumerId); Instant now = instant(); String token = UUID.randomUUID().toString();
    var criteria = new Criteria().andOperator(Criteria.where("state").ne(DispatchState.ACKNOWLEDGED),
        new Criteria().orOperator(Criteria.where("nextAttemptAt").is(null), Criteria.where("nextAttemptAt").lte(now)),
        new Criteria().orOperator(Criteria.where("leaseUntil").is(null), Criteria.where("leaseUntil").lte(now)));
    var query = Query.query(criteria).with(Sort.by(Sort.Direction.ASC, "event.occurredAt", "id"));
    var row = mongo.findAndModify(query, new Update().set("state", DispatchState.CLAIMED).set("consumerId", consumerId)
        .set("leaseToken", token).set("leaseUntil", now.plusSeconds(120)).inc("attempts", 1), FindAndModifyOptions.options().returnNew(true), ComplaintOutbox.class);
    return row == null ? Optional.empty() : Optional.of(new OutboxLease(row.getEvent(), consumerId, token));
  }
  public boolean acknowledge(OutboxLease lease) {
    return mongo.updateFirst(owned(lease), new Update().set("state", DispatchState.ACKNOWLEDGED).set("acknowledgedAt", instant())
        .unset("leaseToken").unset("leaseUntil").unset("lastErrorCode"), ComplaintOutbox.class).getModifiedCount() == 1;
  }
  public boolean retry(OutboxLease lease) {
    return mongo.updateFirst(owned(lease), new Update().set("state", DispatchState.NEW).set("nextAttemptAt", instant().plusSeconds(30))
        .set("lastErrorCode", "CONSUMER_RETRY").unset("leaseToken").unset("leaseUntil"), ComplaintOutbox.class).getModifiedCount() == 1;
  }
  private Query owned(OutboxLease lease) {
    return Query.query(Criteria.where("_id").is(lease.event().eventId()).and("state").is(DispatchState.CLAIMED)
        .and("consumerId").is(lease.consumerId()).and("leaseToken").is(lease.leaseToken()).and("leaseUntil").gt(instant()));
  }
  private Instant instant() { return Instant.ofEpochMilli(clock.millis()); }
}
