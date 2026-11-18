package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Internal persistence port. Public readers must use a server-built QueryScope, never an unscoped list. */
@Repository
public class ComplaintRepository {
  private final MongoTemplate mongo;
  public ComplaintRepository(ComplaintMongoStore store) { mongo = store.template(); }
  private static final Set<String> SORTS = Set.of("createdAt", "updatedAt", "submittedAt", "complaintCode", "status", "kind");
  public Complaint insert(Complaint value) { return mongo.insert(value); }
  public Optional<Complaint> findCommitted(String id) { ComplaintValidation.id(id); return Optional.ofNullable(mongo.findById(new ObjectId(id), Complaint.class)); }
  public Optional<Complaint> findSubmission(String key) { return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("submissionKey").is(key)), Complaint.class)); }
  public Optional<Complaint> findById(String id, QueryScope scope) {
    ComplaintValidation.id(id); return Optional.ofNullable(mongo.findOne(scoped(scope).addCriteria(Criteria.where("_id").is(new ObjectId(id))), Complaint.class));
  }
  public Optional<Complaint> findByCode(String code, QueryScope scope) {
    return Optional.ofNullable(mongo.findOne(scoped(scope).addCriteria(Criteria.where("complaintCode").is(code)), Complaint.class));
  }
  public Page<Complaint> query(QueryScope scope, Filter filter, Pageable page) {
    return query(scope, filter, page, null);
  }
  public Page<Complaint> query(QueryScope scope, Filter filter, Pageable page, String search) {
    validatePage(page); var query = scoped(scope);
    if (search != null && !search.isBlank()) {
      ComplaintValidation.require(search.length() <= 128, "Search too long");
      var literal = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(search.strip()), java.util.regex.Pattern.CASE_INSENSITIVE);
      query.addCriteria(new Criteria().orOperator(Criteria.where("complaintCode").regex(literal), Criteria.where("subject").regex(literal)));
    }
    if (filter != null) {
      ComplaintValidation.require(filter.from() == null || filter.to() == null || !filter.from().isAfter(filter.to()), "Invalid date range");
      if (!filter.statuses().isEmpty()) query.addCriteria(Criteria.where("status").in(filter.statuses()));
      if (!filter.kinds().isEmpty()) query.addCriteria(Criteria.where("kind").in(filter.kinds()));
      if (filter.from() != null || filter.to() != null) {
        var dates = Criteria.where("createdAt"); if (filter.from() != null) dates.gte(filter.from()); if (filter.to() != null) dates.lte(filter.to()); query.addCriteria(dates);
      }
    }
    long count = mongo.count(query, Complaint.class);
    Sort sort = page.getSort().isUnsorted() ? Sort.by(Sort.Direction.DESC, "createdAt") : page.getSort();
    query.with(PageRequest.of(page.getPageNumber(), page.getPageSize(), sort.and(Sort.by(Sort.Direction.DESC, "id"))));
    return new PageImpl<>(mongo.find(query, Complaint.class), page, count);
  }
  Query scoped(QueryScope scope) {
    var query = Query.query(Criteria.where("deleted").ne(true));
    if (scope instanceof ReporterScope s) {
      ComplaintValidation.require(s.reporterType() == ActorType.CUSTOMER || s.reporterType() == ActorType.EMPLOYEE, "Guest reporter enumeration is forbidden");
      ComplaintValidation.id(s.userId()); ComplaintValidation.id(s.homeTenantId());
      query.addCriteria(Criteria.where("reporterType").is(s.reporterType()).and("reporterUserId").is(s.userId()).and("reporterHomeTenantId").is(s.homeTenantId()));
    } else if (scope instanceof QueueScope s) {
      ComplaintValidation.require(s.processing() != null && s.processing().boundary() != null, "Processing boundary required");
      ComplaintValidation.id(s.processing().tenantId()); s.queueIds().forEach(ComplaintValidation::code);
      query.addCriteria(Criteria.where("processingBoundary").is(s.processing().boundary()).and("processingTenantId").is(s.processing().tenantId()).and("currentQueueId").in(s.queueIds()));
      if (!s.fullWithinQueues()) {
        s.limitedComplaintIds().forEach(ComplaintValidation::id);
        query.addCriteria(Criteria.where("_id").in(s.limitedComplaintIds().stream().map(ObjectId::new).toList()));
      }
    } else if (scope instanceof InboxScope s) {
      ComplaintValidation.require(s.processing() != null && s.processing().boundary() != null, "Processing scope required");
      ComplaintValidation.id(s.processing().tenantId());
      query.addCriteria(Criteria.where("processingBoundary").is(s.processing().boundary()).and("processingTenantId").is(s.processing().tenantId()));
      var alternatives = new ArrayList<Criteria>();
      for (var grant : s.grants()) {
        ComplaintValidation.code(grant.queueId()); ComplaintValidation.require(grant.kind() == Kind.INVOICE || grant.kind() == Kind.PLATFORM, "Unsupported processing kind");
        var condition = Criteria.where("currentQueueId").is(grant.queueId()).and("kind").is(grant.kind());
        if (grant.kind() == Kind.INVOICE) {
          ComplaintValidation.id(grant.sourceTenantId()); condition.and("invoiceTenantId").is(grant.sourceTenantId());
          if (!grant.allInvoices()) { grant.invoiceIds().forEach(ComplaintValidation::id); condition.and("invoiceId").in(grant.invoiceIds()); }
        }
        alternatives.add(condition);
      }
      if (alternatives.isEmpty()) query.addCriteria(Criteria.where("$expr").is(false));
      else query.addCriteria(new Criteria().orOperator(alternatives));
    } else if (scope instanceof GuestCaseScope s) { ComplaintValidation.id(s.complaintId()); query.addCriteria(Criteria.where("_id").is(new ObjectId(s.complaintId())).and("reporterType").is(ActorType.GUEST)); }
    else throw new com.ecofood.app.config.BadRequestException("Explicit Complaint query scope required");
    return query;
  }
  public Complaint transition(Complaint current, Status target, PendingCommit commit, Instant now, String actorId) {
    var query = Query.query(Criteria.where("_id").is(current.getId()).and("version").is(current.getVersion()).and("status").is(current.getStatus())
        .and("deleted").ne(true).and("pendingCommit").is(null));
    var update = new Update().set("status", target).set("version", current.getVersion() + 1).set("pendingCommit", commit)
        .set("updatedAt", now).set("updatedBy", actorId).set("nextProjectionAt", now).set("projectionAttempts", 0).unset("lastProjectionError");
    if (target == Status.RESOLVED) update.set("resolvedAt", now); if (target == Status.CLOSED) update.set("closedAt", now);
    return mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), Complaint.class);
  }
  /** Projection metadata uses raw collection updates so @Version/auditing cannot change business sequence. */
  public Complaint changeProcessing(Complaint current, RoutingDecision route, String assignee, PendingCommit commit, Instant now, String actorId) {
    var query = Query.query(Criteria.where("_id").is(current.getId()).and("version").is(current.getVersion()).and("status").is(current.getStatus())
        .and("currentQueueId").is(current.getCurrentQueueId()).and("processingTenantId").is(current.getProcessingTenantId())
        .and("assigneeUserId").is(current.getAssigneeUserId()).and("deleted").ne(true).and("pendingCommit").is(null));
    var update = new Update().set("version", current.getVersion() + 1).set("pendingCommit", commit)
        .set("currentQueueId", route.queueId()).set("processingBoundary", route.processing().boundary()).set("processingTenantId", route.processing().tenantId())
        .set("assigneeUserId", assignee).set("updatedAt", now).set("updatedBy", actorId).set("nextProjectionAt", now).set("projectionAttempts", 0).unset("lastProjectionError");
    return mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), Complaint.class);
  }
  public Complaint claimProjection(String id, String token, Instant now) {
    var criteria = new Criteria().andOperator(Criteria.where("_id").is(new ObjectId(id)).and("pendingCommit").ne(null),
        new Criteria().orOperator(Criteria.where("projectionLeaseUntil").is(null), Criteria.where("projectionLeaseUntil").lte(now)),
        new Criteria().orOperator(Criteria.where("nextProjectionAt").is(null), Criteria.where("nextProjectionAt").lte(now)));
    var document = mongo.findAndModify(Query.query(criteria), new Update().set("projectionLeaseToken", token).set("projectionLeaseUntil", now.plusSeconds(120)),
        FindAndModifyOptions.options().returnNew(true), org.bson.Document.class, "complaint");
    return document == null ? null : mongo.getConverter().read(Complaint.class, document);
  }
  public List<Complaint> pending(Instant now) {
    var query = Query.query(new Criteria().andOperator(Criteria.where("pendingCommit").ne(null),
        new Criteria().orOperator(Criteria.where("nextProjectionAt").is(null), Criteria.where("nextProjectionAt").lte(now)),
        new Criteria().orOperator(Criteria.where("projectionLeaseUntil").is(null), Criteria.where("projectionLeaseUntil").lte(now))));
    return mongo.find(query.limit(25), Complaint.class);
  }
  public void acknowledgeProjection(Complaint row, String token) {
    mongo.updateFirst(ownedProjection(row, token), new Update().unset("pendingCommit").unset("projectionLeaseToken").unset("projectionLeaseUntil")
        .unset("nextProjectionAt").unset("lastProjectionError").set("projectionAttempts", 0), "complaint");
  }
  public void retryProjection(Complaint row, String token, Instant now) {
    long delay = Math.min(3600, 5L << Math.min(10, Math.max(0, row.getProjectionAttempts())));
    mongo.updateFirst(ownedProjection(row, token), new Update().unset("projectionLeaseToken").unset("projectionLeaseUntil")
        .inc("projectionAttempts", 1).set("nextProjectionAt", now.plusSeconds(delay)).set("lastProjectionError", "PROJECTION_FAILED"), "complaint");
  }
  private Query ownedProjection(Complaint row, String token) {
    return Query.query(Criteria.where("_id").is(row.getId()).and("version").is(row.getVersion()).and("projectionLeaseToken").is(token)
        .and("pendingCommit").ne(null));
  }
  static void validatePage(Pageable page) {
    ComplaintValidation.require(page != null && page.isPaged() && page.getPageNumber() >= 0 && page.getPageSize() >= 1 && page.getPageSize() <= 100, "Invalid Complaint page");
    for (var order : page.getSort()) ComplaintValidation.require(SORTS.contains(order.getProperty()), "Unsupported Complaint sort");
  }
}
