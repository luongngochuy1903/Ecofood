package com.ecofood.app.complaint;

import java.time.*;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component @RequiredArgsConstructor
public class ComplaintPublicRateLimiter {
  private final ComplaintMongoStore store;
  private final ComplaintAccessSecrets secrets;
  private final Clock clock;
  public void ip(String ip, boolean tracking) { acquire(tracking ? "tracking-ip" : "submit-ip", ip, tracking ? 30 : 10, 60); }
  public void receipt(String accessId) { acquire("receipt", accessId, 20, 3600); }
  private void acquire(String kind, String identity, int maximum, long seconds) {
    long window = Math.floorDiv(clock.instant().getEpochSecond(), seconds);
    String key = secrets.derive("rate", kind, identity == null ? "unknown" : identity, Long.toString(window));
    var query = Query.query(Criteria.where("_id").is(key).and("count").lt(maximum));
    try {
      store.template().findAndModify(query, new Update().inc("count", 1).setOnInsert("expiresAt", Instant.ofEpochSecond((window + 2) * seconds)),
          FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, "complaint_intake_rate");
    } catch (DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many Complaint requests; retry later"); }
  }
}
