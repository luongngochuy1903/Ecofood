package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class ComplaintEventRepository {
  private final MongoTemplate mongo;
  public ComplaintEventRepository(ComplaintMongoStore store) { mongo = store.template(); }
  public Optional<ComplaintEvent> findById(String eventId) { return Optional.ofNullable(mongo.findById(eventId, ComplaintEvent.class)); }
  public Optional<ComplaintEvent> findCommand(String commandKey) {
    return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("commandKey").is(commandKey)), ComplaintEvent.class));
  }
  public void append(ComplaintEvent event) {
    try { mongo.insert(event); }
    catch (DuplicateKeyException ex) {
      var winner = mongo.findById(event.eventId(), ComplaintEvent.class);
      if (!event.equals(winner)) throw new IllegalStateException("Immutable Complaint history conflict");
    }
  }
  public Page<ComplaintEvent> query(String complaintId, long cutoff, Long excludedSequence, boolean reporterOnly, Pageable page) {
    ComplaintValidation.id(complaintId);
    ComplaintValidation.require(page != null && page.isPaged() && page.getPageSize() <= 100 && page.getPageSize() >= 1, "Invalid history page");
    var sequence = Criteria.where("sequence").lte(cutoff); if (excludedSequence != null) sequence.ne(excludedSequence);
    var query = Query.query(Criteria.where("complaintId").is(complaintId)).addCriteria(sequence);
    if (reporterOnly) query.addCriteria(Criteria.where("visibility").is(Visibility.PUBLIC));
    long total = mongo.count(query, ComplaintEvent.class);
    query.with(PageRequest.of(page.getPageNumber(), page.getPageSize(), Sort.by(Sort.Direction.ASC, "sequence")));
    return new PageImpl<>(mongo.find(query, ComplaintEvent.class), page, total);
  }
}
