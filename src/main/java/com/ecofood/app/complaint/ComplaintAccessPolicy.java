package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.ForbiddenException;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;

/** Case-level checks supplement permissions; no RoleLevel/SYSTEM or arbitrary tenant bypass. */
@RequiredArgsConstructor
public class ComplaintAccessPolicy {
  private final ComplaintRoutingDirectory directory;
  private final Clock clock;

  /** Intake supplies a verified Actor/proof; this adds live employee permission checks. */
  public void requireSubmit(Kind kind, Actor actor) {
    if (kind == null || actor == null || actor.type() == null) throw denied();
    if (actor.type() == ActorType.GUEST) { if (kind != Kind.INVOICE) throw denied(); return; }
    if (!actor.permissions().contains(Permission.COMPLAINT_CREATE)) throw denied();
    if (kind == Kind.INTERNAL) {
      if (actor.type() != ActorType.EMPLOYEE || !directory.isActiveEmployee(actor.userId(), actor.tenantId())
          || !directory.hasPermission(actor.userId(), actor.tenantId(), Permission.COMPLAINT_CREATE)) throw denied();
    } else if (actor.type() != ActorType.CUSTOMER) throw denied();
  }
  /** Candidate case-level scope is checked in addition to catalogue-level queue eligibility. */
  public void requireReceivingQueue(ComplaintCase complaint) {
    var queue = queue(complaint);
    // A saved Invoice always has a seller inbox. Handler availability never changes reporter ownership.
    // Processing still requires live Admin authority + the operation's permission on every request.
    if (directory.isInvoiceAuthorityCase(complaint)) return;
    boolean available = directory.memberships(queue.id()).stream().anyMatch(member -> {
      if (!ComplaintMemberships.eligible(directory, queue, member, QueueCapability.PROCESS)) return false;
      var target = new Actor(ActorType.EMPLOYEE, member.userId(), queue.scope().tenantId(), Set.of(Permission.COMPLAINT_READ, Permission.COMPLAINT_PROCESS));
      return directory.inDataScope(target, complaint, Permission.COMPLAINT_READ) && directory.inDataScope(target, complaint, Permission.COMPLAINT_PROCESS);
    });
    if (!available) throw denied();
  }
  public void requireQueueRead(ComplaintCase complaint, Actor actor) { requireHandler(complaint, actor, QueueCapability.READ); }
  /** A receipt reveals only this actor's original result, even after the case moved to the next queue. */
  public void requireCommandReceipt(ComplaintCase current, ComplaintEvent event, Actor actor, VerifiedGuestAccess guest,
      ComplaintCoreContracts.Operation operation) {
    if (actor.type() != ActorType.EMPLOYEE || isReporter(current, actor, guest)) { requireRead(current, actor, guest); return; }
    if (event.actorType() != actor.type() || !Objects.equals(event.actorId(), actor.userId())) throw denied();
    var source = directory.queue(event.fromQueueId()).orElseThrow(this::denied);
    var change = event.processingChange();
    var processing = change == null ? source.scope() : change.fromScope();
    if (!Objects.equals(source.scope(), processing)) throw denied();
    var snapshot = new ComplaintCase(current.id(), current.complaintCode(), current.kind(), current.categoryCode(), current.subject(), current.description(),
        current.reporter(), current.invoice(), current.sourceTenantId(), current.reporterOriginQueueId(), processing, source.id(),
        change == null ? null : change.fromAssigneeUserId(), current.routingPolicyId(), current.routingPolicyVersion(), event.fromStatus(), current.version(), current.audit());
    if (operation == ComplaintCoreContracts.Operation.ESCALATE) requireEscalate(snapshot, actor);
    else requireHandler(snapshot, actor, operation == ComplaintCoreContracts.Operation.ASSIGN ? QueueCapability.ASSIGN : QueueCapability.PROCESS);
  }

  public void requireRead(ComplaintCase complaint, Actor actor, VerifiedGuestAccess guest) {
    if (isReporter(complaint, actor, guest)) return;
    requireHandler(complaint, actor, QueueCapability.READ);
  }
  public void requireTransition(ComplaintCase complaint, Actor actor, VerifiedGuestAccess guest, Status target) {
    ComplaintStateMachine.requireTransition(complaint.status(), target);
    if ((complaint.status() == Status.WAITING_REPORTER || target == Status.CLOSED) && isReporter(complaint, actor, guest)) return;
    var membership = requireHandler(complaint, actor, QueueCapability.PROCESS);
    if (target == Status.CLOSED && membership.authority() == Authority.NONE) throw denied();
  }
  public void requireEscalate(ComplaintCase complaint, Actor actor) {
    if (complaint.status() != Status.IN_PROGRESS) throw new com.ecofood.app.config.BadRequestException("Escalation requires an in-progress complaint");
    var membership = requireHandler(complaint, actor, QueueCapability.ESCALATE);
    if (queue(complaint).purpose() == QueuePurpose.FINAL_AUTHORITY && membership.authority() == Authority.NONE) throw denied();
  }
  public void requireAssign(ComplaintCase complaint, Actor actor, String targetUserId) {
    if (complaint.status() == Status.RESOLVED || complaint.status() == Status.CLOSED) throw denied();
    requireHandler(complaint, actor, QueueCapability.ASSIGN);
    var queue = queue(complaint);
    if (!RoutingPolicyValidator.text(targetUserId) || directory.memberships(queue.id()).stream().noneMatch(m -> targetUserId.equals(m.userId())
        && ComplaintMemberships.eligible(directory, queue, m, QueueCapability.PROCESS))) throw denied();
    var target = new Actor(ActorType.EMPLOYEE, targetUserId, queue.scope().tenantId(), Set.of(Permission.COMPLAINT_READ, Permission.COMPLAINT_PROCESS));
    if (!directory.inDataScope(target, complaint, Permission.COMPLAINT_READ) || !directory.inDataScope(target, complaint, Permission.COMPLAINT_PROCESS)) throw denied();
  }

  public boolean isReporter(ComplaintCase complaint, Actor actor, VerifiedGuestAccess guest) {
    if (complaint == null || !RoutingPolicyValidator.text(complaint.id()) || actor == null || actor.type() == null || complaint.reporter() == null) return false;
    var reporter = complaint.reporter();
    if (actor.type() == ActorType.GUEST) return reporter.type() == ActorType.GUEST && guest != null
        && Objects.equals(complaint.id(), guest.complaintId()) && guest.expiresAt() != null && guest.expiresAt().isAfter(clock.instant());
    return actor.type() == reporter.type() && RoutingPolicyValidator.text(actor.userId()) && actor.userId().equals(reporter.userId())
        && RoutingPolicyValidator.text(actor.tenantId()) && actor.tenantId().equals(reporter.homeTenantId())
        && actor.permissions().contains(Permission.COMPLAINT_READ)
        && (actor.type() == ActorType.CUSTOMER || actor.type() == ActorType.EMPLOYEE && directory.isActiveEmployee(actor.userId(), actor.tenantId())
            && directory.hasPermission(actor.userId(), actor.tenantId(), Permission.COMPLAINT_READ));
  }

  private ComplaintQueueMembership requireHandler(ComplaintCase complaint, Actor actor, QueueCapability operation) {
    if (actor == null || actor.type() != ActorType.EMPLOYEE || complaint == null || complaint.processing() == null) throw denied();
    var queue = queue(complaint); var permission = ComplaintMemberships.permission(operation);
    if (!Objects.equals(actor.tenantId(), queue.scope().tenantId()) || !actor.permissions().contains(Permission.COMPLAINT_READ)
        || !actor.permissions().contains(permission) || !directory.inDataScope(actor, complaint, Permission.COMPLAINT_READ)
        || !directory.inDataScope(actor, complaint, permission)) throw denied();
    var matches = directory.memberships(queue.id()).stream().filter(m -> Objects.equals(actor.userId(), m.userId())
        && ComplaintMemberships.eligible(directory, queue, m, operation)).toList();
    if (matches.size() != 1) throw denied();
    var membership = matches.get(0);
    if (operation != QueueCapability.READ && operation != QueueCapability.ASSIGN && complaint.assignedUserId() != null
        && !complaint.assignedUserId().equals(actor.userId()) && membership.authority() == Authority.NONE) throw denied();
    return membership;
  }
  private ComplaintQueue queue(ComplaintCase complaint) {
    var queue = directory.queue(complaint.assignedQueueId()).orElseThrow(this::denied);
    if (!queue.active() || !Objects.equals(queue.scope(), complaint.processing()) || queue.purpose() == QueuePurpose.REPORTING_ORIGIN) throw denied();
    var tenant = directory.tenant(queue.scope().tenantId()).orElseThrow(this::denied);
    if (!tenant.active() || tenant.platform() != (queue.scope().boundary() == Boundary.PLATFORM)
        || tenant.platform() && !tenant.id().equals(directory.platformTenantId())) throw denied();
    return queue;
  }
  private ForbiddenException denied() { return new ForbiddenException("Complaint access denied"); }
}
