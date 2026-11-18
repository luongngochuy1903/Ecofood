package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.complaint.ComplaintProcessingContracts.*;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.core.dto.PageResponse;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ComplaintProcessingService {
  private final MongoComplaintRoutingDirectory directory;
  private final ComplaintRepository repository;
  private final ComplaintWorkflowService workflow;
  private final ComplaintHistoryService history;
  private final ComplaintAccessPolicy access;
  private final RoutingPolicyResolver routing;
  private final ComplaintInboxRepository inbox;
  private final com.ecofood.app.transaction.repository.InvoiceRepository invoices;

  public PageResponse<InboxSummary> search(int page, int size, Kind kind, Status status, Instant from, Instant to, String sortBy, Sort.Direction direction, Search search) {
    return directory.inSnapshot(() -> {
      var actor = ComplaintActors.employee(directory, Permission.COMPLAINT_READ);
      if (kind == Kind.INTERNAL) throw new BadRequestException("Unsupported kind");
      var rows = inbox.query(directory.inboxScope(actor), new Filter(status == null ? Set.of() : Set.of(status), kind == null ? Set.of(Kind.INVOICE, Kind.PLATFORM) : Set.of(kind), from, to), search, PageRequest.of(page, size, Sort.by(direction, sortBy)));
      return PageResponse.<InboxSummary>builder().content(rows.getContent().stream().map(item -> {
        var row = item.complaint(); return new InboxSummary(summary(row), item.invoiceCode(), queueName(row), assigneeName(row), categoryName(row), row.asCase().sourceTenantId());
      }).toList()).page(page).size(size).totalElements(rows.getTotalElements()).totalPages(rows.getTotalPages()).build();
    });
  }
  public InboxOptions options() {
    return directory.inSnapshot(() -> {
      var actor = ComplaintActors.employee(directory, Permission.COMPLAINT_READ); var scope = directory.inboxScope(actor);
      var queues = scope.grants().stream().map(InboxGrant::queueId).distinct().sorted().map(id -> new Choice(id, directory.queue(id).orElseThrow().name())).toList();
      var categories = directory.categories().stream().filter(c -> c.active() && scope.grants().stream().anyMatch(g -> g.kind() == c.kind()
          && (c.ownerTenantId() == null || Objects.equals(c.ownerTenantId(), g.kind() == Kind.PLATFORM ? directory.platformTenantId() : g.sourceTenantId()))))
          .map(c -> new Choice(c.code(), c.name())).distinct().sorted(Comparator.comparing(Choice::value)).toList();
      return new InboxOptions(scope.processing().boundary(), actor.tenantId(), queues, categories);
    });
  }

  public PageResponse<Summary> list(int page, int size, Kind kind, Status status, Instant from, Instant to, String sortBy, Sort.Direction direction) {
    return directory.inSnapshot(() -> {
      var actor = ComplaintActors.employee(directory, Permission.COMPLAINT_READ);
      if (kind == Kind.INTERNAL) throw new BadRequestException("INTERNAL is not supported by this inbox");
      var rows = repository.query(directory.inboxScope(actor), new Filter(status == null ? Set.of() : Set.of(status),
          kind == null ? Set.of(Kind.INVOICE, Kind.PLATFORM) : Set.of(kind), from, to), PageRequest.of(page, size, Sort.by(direction, sortBy)));
      return PageResponse.<Summary>builder().content(rows.getContent().stream().map(this::summary).toList()).page(page).size(size)
          .totalElements(rows.getTotalElements()).totalPages(rows.getTotalPages()).build();
    });
  }
  public Detail detail(String id) {
    return directory.inSnapshot(() -> {
      var actor = ComplaintActors.employee(directory, Permission.COMPLAINT_READ); var row = readable(id, actor);
      return new Detail(summary(row), row.getDescription(), row.getReporterType(), row.isIdentityVerified(), row.asCase().invoice(),
          row.getInvoiceItemReferences(), row.getRoutingPolicyId(), row.getRoutingPolicyVersion(), row.getResolvedAt(), capabilities(row, actor), invoiceView(row), categoryName(row), queueName(row), assigneeName(row));
    });
  }
  public PageResponse<HistoryEntry> history(String id, int page, int size) {
    return directory.inSnapshot(() -> {
      var actor = ComplaintActors.employee(directory, Permission.COMPLAINT_READ); readable(id, actor);
      var events = history.processing(id, actor, page, size);
      return PageResponse.<HistoryEntry>builder().content(events.getContent().stream().map(e -> new HistoryEntry(e.eventId(), e.sequence(), e.eventType(),
          e.actorType(), e.actorId(), e.fromStatus(), e.toStatus(), e.fromQueueId(), e.toQueueId(), e.reason(), e.visibility(), e.occurredAt(), e.processingChange())).toList())
          .page(page).size(size).totalElements(events.getTotalElements()).totalPages(events.getTotalPages()).build();
    });
  }
  public CommandResult assign(String id, Assignment request) { return mutate(id, request.action(), Operation.ASSIGN, request.assigneeUserId()); }
  public CommandResult action(String id, Action request, Operation operation) { return mutate(id, request, operation, null); }
  private CommandResult mutate(String id, Action request, Operation operation, String assignee) {
    return directory.inSnapshot(() -> {
      Permission permission = operation == Operation.ASSIGN ? Permission.COMPLAINT_ASSIGN : operation == Operation.ESCALATE ? Permission.COMPLAINT_ESCALATE : Permission.COMPLAINT_PROCESS;
      var actor = ComplaintActors.employee(directory, permission);
      // Only a kind check here: Core authorizes current processing scope, or the original command receipt on retry.
      var row = repository.findCommitted(id).filter(r -> !Boolean.TRUE.equals(r.getDeleted()) && r.getKind() != Kind.INTERNAL)
          .orElseThrow(() -> new IllegalArgumentException("Complaint unavailable"));
      var command = new WorkflowCommand(row.getIdAsString(), request.expectedVersion(), request.requestId(), request.requestId(), actor, null, request.reason(),
          operation == Operation.REQUEST_INFORMATION || operation == Operation.RESOLVE ? Visibility.PUBLIC : Visibility.INTERNAL);
      return switch (operation) {
        case ASSIGN -> workflow.assign(command, assignee);
        case START_PROCESSING -> workflow.startProcessing(command);
        case REQUEST_INFORMATION -> workflow.requestAdditionalInformation(command);
        case RESUME_PROCESSING -> workflow.resumeProcessing(command);
        case RESOLVE -> workflow.resolve(command);
        case ESCALATE -> workflow.escalate(command, routing);
        default -> throw new BadRequestException("Unsupported processing operation");
      };
    });
  }
  private Complaint readable(String id, Actor actor) {
    var row = repository.findById(id, directory.inboxScope(actor)).orElseThrow(() -> new IllegalArgumentException("Complaint unavailable"));
    access.requireQueueRead(row.asCase(), actor); return row;
  }
  private Summary summary(Complaint row) {
    return new Summary(row.getIdAsString(), row.getComplaintCode(), row.getKind(), row.getCategoryCode(), row.getSubject(), row.getStatus(), row.getVersion(),
        row.getProcessingBoundary(), row.getProcessingTenantId(), row.getCurrentQueueId(), row.getAssigneeUserId(), row.getInvoiceId(), row.getDistributionPartnerId(), row.getSubmittedAt(), row.getUpdatedAt());
  }
  private Capabilities capabilities(Complaint row, Actor actor) {
    var allowed = new ArrayList<String>(); var candidates = new ArrayList<Candidate>();
    if (row.getPendingCommit() != null) return new Capabilities(row.getVersion(), List.of(), List.of());
    for (var member : directory.memberships(row.getCurrentQueueId())) {
      if (permitted(() -> access.requireAssign(row.asCase(), actor, member.userId()))) candidates.add(new Candidate(member.userId(), directory.employeeLabel(member.userId(), actor.tenantId())));
    }
    if (!candidates.isEmpty()) allowed.add("assign");
    if (row.getStatus() == Status.SUBMITTED && permitted(() -> access.requireTransition(row.asCase(), actor, null, Status.IN_PROGRESS))) allowed.add("start");
    if (row.getStatus() == Status.WAITING_REPORTER && permitted(() -> access.requireTransition(row.asCase(), actor, null, Status.IN_PROGRESS))) allowed.add("resume");
    if (row.getStatus() == Status.IN_PROGRESS) {
      if (permitted(() -> access.requireTransition(row.asCase(), actor, null, Status.WAITING_REPORTER))) allowed.add("request-information");
      if (permitted(() -> access.requireTransition(row.asCase(), actor, null, Status.RESOLVED))) allowed.add("resolve");
      if (permitted(() -> {
        var route = routing.escalate(row.asCase(), actor, "Check escalation eligibility"); var c = row.asCase();
        access.requireReceivingQueue(new ComplaintCase(c.id(), c.complaintCode(), c.kind(), c.categoryCode(), c.subject(), c.description(), c.reporter(), c.invoice(), c.sourceTenantId(),
            c.reporterOriginQueueId(), route.processing(), route.queueId(), null, c.routingPolicyId(), c.routingPolicyVersion(), c.status(), c.version(), c.audit()));
      })) allowed.add("escalate");
    }
    return new Capabilities(row.getVersion(), List.copyOf(allowed), candidates.stream().distinct().sorted(Comparator.comparing(Candidate::displayName)).toList());
  }
  private boolean permitted(Runnable check) { try { check.run(); return true; } catch (com.ecofood.app.config.ForbiddenException | BadRequestException ex) { return false; } }
  private InvoiceView invoiceView(Complaint row) {
    if (row.getKind() != Kind.INVOICE) return null;
    return invoices.findById(row.getInvoiceId()).filter(i -> Objects.equals(i.getTenantId(), row.getInvoiceTenantId()) && Objects.equals(i.getDistributionPartnerId(), row.getDistributionPartnerId()))
        .map(i -> new InvoiceView(i.getInvoiceNumber(), i.getTransactionTime(), i.getCurrency(), i.getTotalAmount(), i.getItems(), row.getInvoiceSubmittedBy() == null ? i.getSubmittedBy() : row.getInvoiceSubmittedBy())).orElse(null);
  }
  private String queueName(Complaint row) { return directory.queue(row.getCurrentQueueId()).map(ComplaintQueue::name).orElse(row.getCurrentQueueId()); }
  private String assigneeName(Complaint row) {
    if (row.getAssigneeUserId() == null) return null;
    return directory.memberships(row.getCurrentQueueId()).stream().filter(m -> m.userId().equals(row.getAssigneeUserId()) && m.active()).findFirst()
        .map(m -> directory.employeeLabel(m.userId(), row.getProcessingTenantId())).orElse(row.getAssigneeUserId());
  }
  private String categoryName(Complaint row) {
    try { return ComplaintCategories.require(directory.categories(), row.getKind(), row.getCategoryCode(), row.getKind() == Kind.PLATFORM ? directory.platformTenantId() : row.getInvoiceTenantId()).name(); }
    catch (BadRequestException ex) { return row.getCategoryCode(); }
  }
}
