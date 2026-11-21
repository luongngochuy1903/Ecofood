package com.ecofood.app.complaint;

import com.ecofood.app.submission.entity.Submission;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable outbox embedded with the Complaint write; existing Submission is the only event system. */
@Service
@RequiredArgsConstructor
public class ComplaintHandoff {
  private final MongoTemplate mongo;
  @Scheduled(fixedDelayString="${ecofood.complaint.handoff-poll-ms:10000}",initialDelayString="${ecofood.notification.initial-delay-ms:20000}")
  public void poll() {
    try { for(var c:mongo.find(Query.query(Criteria.where("handoffs.pending").is(true)).limit(50),Complaint.class)) deliver(c); }
    catch(RuntimeException unavailable) { /* Persisted handoffs remain pending. */ }
  }
  public void deliver(Complaint c) {
    if(c.getHandoffs()==null) return;
    for(int i=0;i<c.getHandoffs().size();i++) {
      var handoff=c.getHandoffs().get(i); if(!handoff.pending()) continue;
      try {
        insertSubmission(handoff.submission());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId()).and("handoffs."+i+".submission.id").is(handoff.submission().id())),
            new Update().set("handoffs."+i+".pending",false).unset("handoffError"),Complaint.class);
      } catch(RuntimeException failure) {
        recordFailure(c);
      }
    }
  }
  private void insertSubmission(Submission submission) {
    try { mongo.insert(submission); }
    catch(DuplicateKeyException duplicate) {
      var existing=mongo.findById(submission.id(),Submission.class);
      if(!submission.equals(existing)) throw new IllegalStateException("COMPLAINT_EVENT_CONFLICT");
    }
  }
  private void recordFailure(Complaint c) {
    try { mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())),new Update().set("handoffError","SUBMISSION_HANDOFF_RETRY"),Complaint.class); }
    catch(RuntimeException unavailable) { /* Retry from durable source. */ }
  }
}
