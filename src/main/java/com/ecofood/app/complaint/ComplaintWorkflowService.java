package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.config.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;

/** Internal domain operations; no generic client-controlled status mutation or Intake API. */
@Service @RequiredArgsConstructor
public class ComplaintWorkflowService {
  private final ComplaintRepository complaints;
  private final ComplaintEventRepository events;
  private final ComplaintValidation validation;
  private final ComplaintAuthorization authorization;
  private final ComplaintProjectionService projection;
  private final Clock clock;

  public CommandResult submit(SubmitCommand command) {
    validation.submissionShape(command); var access = authorization.requirePolicy(); access.requireSubmit(command.kind(), command.actor());
    String key = ComplaintCommandIdentity.submitKey(command), fingerprint = ComplaintCommandIdentity.submitFingerprint(command);
    var existing = complaints.findSubmission(key);
    if (existing.isPresent()) return submittedRetry(existing.get(), fingerprint);
    var sourceInvoice = validation.invoiceSource(command);
    Instant now = now(); var row = new Complaint(); row.setId(new ObjectId()); row.setComplaintCode("CMP-" + row.getIdAsString().toUpperCase(Locale.ROOT));
    row.setVersion(0L); row.setKind(command.kind()); row.setCategoryCode(command.categoryCode()); row.setSubject(command.subject().strip()); row.setDescription(command.description().strip());
    var actor = command.actor(); row.setReporterType(actor.type()); row.setReporterUserId(actor.userId()); row.setReporterHomeTenantId(actor.tenantId());
    row.setIdentityVerified(actor.type() != ActorType.GUEST); row.setOriginTenantId(command.kind() == Kind.INTERNAL ? actor.tenantId() : null);
    if (command.invoice() != null) { row.setInvoiceId(command.invoice().invoiceId()); row.setInvoiceTenantId(command.invoice().tenantId()); row.setDistributionPartnerId(command.invoice().distributionPartnerId()); }
    if (sourceInvoice != null) row.setInvoiceSubmittedBy(sourceInvoice.getSubmittedBy());
    row.setInvoiceItemReferences(command.invoiceItemReferences().stream().sorted(Comparator.comparingInt(InvoiceItemReference::lineIndex)).toList());
    var route = command.routing(); row.setSubmittedRouting(route); row.setReporterOriginQueueId(route.reporterOriginQueueId()); row.setProcessingBoundary(route.processing().boundary());
    row.setProcessingTenantId(route.processing().tenantId()); row.setCurrentQueueId(route.queueId()); row.setRoutingPolicyId(route.policyId());
    row.setRoutingPolicyVersion(route.policyVersion()); row.setRoutingState(RoutingState.ASSIGNED); row.setStatus(Status.SUBMITTED);
    row.setCorrelationId(command.correlationId() == null ? command.requestId() : command.correlationId()); row.setSubmittedAt(now);
    row.setCreatedAt(now); row.setUpdatedAt(now); row.setCreatedBy(actorId(actor)); row.setUpdatedBy(actorId(actor));
    access.requireReceivingQueue(row.asCase());
    var event = event(row, 0, EventType.COMPLAINT_SUBMITTED, actor, null, Status.SUBMITTED, "Complaint submitted", Visibility.PUBLIC,
        row.getCorrelationId(), key, fingerprint, now);
    row.setSubmissionKey(key); row.setSubmissionFingerprint(fingerprint); row.setSubmissionResult(event.result());
    row.setPendingCommit(new PendingCommit(event)); row.setNextProjectionAt(now);
    try { complaints.insert(row); }
    catch (DuplicateKeyException ex) {
      return complaints.findSubmission(key).map(winner -> submittedRetry(winner, fingerprint)).orElseThrow(() -> ex);
    }
    projection.tryProject(row.getIdAsString()); return event.result();
  }
  public CommandResult startProcessing(WorkflowCommand c) { return execute(Operation.START_PROCESSING, Status.IN_PROGRESS, c); }
  public CommandResult requestAdditionalInformation(WorkflowCommand c) { return execute(Operation.REQUEST_INFORMATION, Status.WAITING_REPORTER, c); }
  public CommandResult resumeProcessing(WorkflowCommand c) { return execute(Operation.RESUME_PROCESSING, Status.IN_PROGRESS, c); }
  public CommandResult resolve(WorkflowCommand c) { return execute(Operation.RESOLVE, Status.RESOLVED, c); }
  public CommandResult close(WorkflowCommand c) { return execute(Operation.CLOSE, Status.CLOSED, c); }
  public CommandResult assign(WorkflowCommand c, String assigneeUserId) { ComplaintValidation.id(assigneeUserId); return changeProcessing(c, assigneeUserId, null); }
  public CommandResult escalate(WorkflowCommand c, RoutingPolicyResolver resolver) { return changeProcessing(c, null, Objects.requireNonNull(resolver)); }

  private CommandResult changeProcessing(WorkflowCommand command, String assignee, RoutingPolicyResolver resolver) {
    validation.workflow(command); var access = authorization.requirePolicy();
    var row = complaints.findCommitted(command.complaintId()).filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
        .orElseThrow(() -> new IllegalArgumentException("Complaint not found"));
    ComplaintValidation.require(row.getKind() == Kind.INVOICE || row.getKind() == Kind.PLATFORM, "Unsupported processing kind");
    Operation operation = resolver == null ? Operation.ASSIGN : Operation.ESCALATE;
    String key = ComplaintCommandIdentity.workflowKey(command), fingerprint = ComplaintCommandIdentity.assignmentFingerprint(operation, command, assignee);
    var prior = receiptEvent(row, key);
    if (prior.isPresent()) {
      access.requireCommandReceipt(row.asCase(), prior.get(), command.actor(), command.guestAccess(), operation);
      var result = repeat(prior.get(), fingerprint); projection.tryProject(row.getIdAsString()); return result;
    }
    access.requireQueueRead(row.asCase(), command.actor());
    row = ready(row);
    prior = receiptEvent(row, key);
    if (prior.isPresent()) { access.requireCommandReceipt(row.asCase(), prior.get(), command.actor(), command.guestAccess(), operation); return repeat(prior.get(), fingerprint); }
    if (row.getVersion() != command.expectedVersion()) throw stale();
    RoutingDecision route;
    if (resolver == null) {
      access.requireAssign(row.asCase(), command.actor(), assignee);
      route = new RoutingDecision(row.getRoutingPolicyId(), row.getRoutingPolicyVersion(), row.getCurrentQueueId(),
          new ProcessingScope(row.getProcessingBoundary(), row.getProcessingTenantId()), row.getReporterOriginQueueId());
    } else {
      // Resolver revalidates the pinned published policy, exact edge, current source and escalation authority.
      route = resolver.escalate(row.asCase(), command.actor(), command.reason());
      var target = row.asCase();
      var candidate = new ComplaintCase(target.id(), target.complaintCode(), target.kind(), target.categoryCode(), target.subject(), target.description(),
          target.reporter(), target.invoice(), target.sourceTenantId(), target.reporterOriginQueueId(), route.processing(), route.queueId(), null,
          target.routingPolicyId(), target.routingPolicyVersion(), target.status(), target.version(), target.audit());
      access.requireReceivingQueue(candidate);
    }
    Instant now = now(); long version = row.getVersion() + 1; String id = row.getIdAsString() + ":" + (version + 1);
    var result = new CommandResult(row.getIdAsString(), row.getComplaintCode(), row.getStatus(), version, id, version + 1, now);
    var change = new ProcessingChange(new ProcessingScope(row.getProcessingBoundary(), row.getProcessingTenantId()), route.processing(),
        row.getAssigneeUserId(), assignee, row.getRoutingPolicyId(), row.getRoutingPolicyVersion());
    var event = new ComplaintEvent(id, row.getIdAsString(), version + 1, resolver == null ? EventType.COMPLAINT_ASSIGNED : EventType.COMPLAINT_ESCALATED,
        command.actor().type(), command.actor().userId(), row.getStatus(), row.getStatus(), row.getCurrentQueueId(), route.queueId(), command.reason().strip(),
        command.visibility(), now, command.correlationId() == null ? command.requestId() : command.correlationId(), key, fingerprint, result, change);
    var updated = complaints.changeProcessing(row, route, assignee, new PendingCommit(event), now, actorId(command.actor()));
    if (updated == null) {
      var winner = complaints.findCommitted(row.getIdAsString()).orElseThrow();
      var completed = receiptEvent(winner, key).orElseThrow(this::stale);
      access.requireCommandReceipt(winner.asCase(), completed, command.actor(), command.guestAccess(), operation); return repeat(completed, fingerprint);
    }
    projection.tryProject(row.getIdAsString()); return result;
  }
  private Complaint ready(Complaint row) {
    if (row.getPendingCommit() == null) return row;
    projection.tryProject(row.getIdAsString()); var refreshed = complaints.findCommitted(row.getIdAsString()).orElseThrow();
    if (refreshed.getPendingCommit() != null) throw new IllegalStateException("Complaint commit projection pending; retry later");
    return refreshed;
  }

  private CommandResult execute(Operation operation, Status target, WorkflowCommand command) {
    validation.workflow(command); var access = authorization.requirePolicy();
    var row = complaints.findCommitted(command.complaintId()).filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
        .orElseThrow(() -> new IllegalArgumentException("Complaint not found"));
    String key = ComplaintCommandIdentity.workflowKey(command), fingerprint = ComplaintCommandIdentity.workflowFingerprint(operation, command);
    var prior = receiptEvent(row, key);
    if (prior.isPresent()) { access.requireCommandReceipt(row.asCase(), prior.get(), command.actor(), command.guestAccess(), operation); return repeat(prior.get(), fingerprint); }
    access.requireRead(row.asCase(), command.actor(), command.guestAccess());
    if (row.getPendingCommit() != null) {
      projection.tryProject(row.getIdAsString()); row = complaints.findCommitted(row.getIdAsString()).orElseThrow();
      if (row.getPendingCommit() != null) throw new IllegalStateException("Complaint commit projection pending; retry later");
    }
    prior = receiptEvent(row, key);
    if (prior.isPresent()) { access.requireCommandReceipt(row.asCase(), prior.get(), command.actor(), command.guestAccess(), operation); return repeat(prior.get(), fingerprint); }
    if (row.getVersion() != command.expectedVersion()) throw stale();
    // Distinguish START from RESUME even though both target IN_PROGRESS.
    Status required = switch (operation) { case START_PROCESSING -> Status.SUBMITTED; case RESUME_PROCESSING -> Status.WAITING_REPORTER;
      case REQUEST_INFORMATION, RESOLVE -> Status.IN_PROGRESS; case CLOSE -> Status.RESOLVED;
      default -> throw new BadRequestException("Not a status operation"); };
    if (row.getStatus() != required) throw new BadRequestException("Invalid Complaint workflow operation");
    access.requireTransition(row.asCase(), command.actor(), command.guestAccess(), target);
    if (access.isReporter(row.asCase(), command.actor(), command.guestAccess()) && command.visibility() != Visibility.PUBLIC)
      throw new BadRequestException("Reporter actions cannot create internal history");
    Instant now = now(); long version = row.getVersion() + 1;
    EventType type = operation == Operation.RESOLVE ? EventType.COMPLAINT_RESOLVED : operation == Operation.CLOSE ? EventType.COMPLAINT_CLOSED : EventType.COMPLAINT_STATUS_CHANGED;
    var event = event(row, version, type, command.actor(), row.getStatus(), target, command.reason().strip(), command.visibility(),
        command.correlationId() == null ? command.requestId() : command.correlationId(), key, fingerprint, now);
    var updated = complaints.transition(row, target, new PendingCommit(event), now, actorId(command.actor()));
    if (updated == null) {
      var winner = complaints.findCommitted(row.getIdAsString()).orElseThrow();
      return receipt(winner, key, fingerprint).orElseThrow(this::stale);
    }
    projection.tryProject(row.getIdAsString()); return event.result();
  }
  private Optional<CommandResult> receipt(Complaint row, String key, String fingerprint) {
    return receiptEvent(row, key).map(event -> repeat(event, fingerprint));
  }
  private Optional<ComplaintEvent> receiptEvent(Complaint row, String key) {
    if (row.getPendingCommit() != null && key.equals(row.getPendingCommit().event().commandKey()))
      return Optional.of(row.getPendingCommit().event());
    return events.findCommand(key);
  }
  private CommandResult repeat(ComplaintEvent event, String fingerprint) {
    if (!fingerprint.equals(event.payloadFingerprint())) throw new IllegalStateException("Command request identifier already used with another payload");
    return event.result();
  }
  private CommandResult submittedRetry(Complaint row, String fingerprint) {
    if (!fingerprint.equals(row.getSubmissionFingerprint())) throw new IllegalStateException("Submission request identifier already used with another payload");
    projection.tryProject(row.getIdAsString()); return row.getSubmissionResult();
  }
  private ComplaintEvent event(Complaint row, long version, EventType type, Actor actor, Status from, Status target,
      String reason, Visibility visibility, String correlation, String key, String fingerprint, Instant now) {
    long sequence = version + 1; String id = row.getIdAsString() + ":" + sequence;
    var result = new CommandResult(row.getIdAsString(), row.getComplaintCode(), target, version, id, sequence, now);
    return new ComplaintEvent(id, row.getIdAsString(), sequence, type, actor.type(), actor.userId(), from, target,
        from == null ? null : row.getCurrentQueueId(), row.getCurrentQueueId(), reason, visibility, now, correlation, key, fingerprint, result,
        from == null ? null : new ProcessingChange(new ProcessingScope(row.getProcessingBoundary(), row.getProcessingTenantId()),
            new ProcessingScope(row.getProcessingBoundary(), row.getProcessingTenantId()), row.getAssigneeUserId(), row.getAssigneeUserId(), row.getRoutingPolicyId(), row.getRoutingPolicyVersion()));
  }
  private String actorId(Actor actor) { return actor.userId() == null ? "GUEST_CAPABILITY" : actor.userId(); }
  private OptimisticLockingFailureException stale() { return new OptimisticLockingFailureException("Complaint version changed; reload before retrying"); }
  private Instant now() { return Instant.ofEpochMilli(clock.millis()); }
}
