package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.ForbiddenException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import static com.ecofood.app.complaint.RoutingPolicyValidator.*;

/** Resolves destinations from trusted context and immutable policies; no destination parameter is accepted. */
@RequiredArgsConstructor
public class RoutingPolicyResolver {
  private final ComplaintRoutingDirectory directory;
  private final RoutingPolicyValidator validator;
  private final ComplaintAccessPolicy access;

  public RoutingDecision invoice(VerifiedInvoice invoice, Actor actor, String category) {
    require(invoice != null && text(invoice.invoiceId()) && text(invoice.tenantId()) && text(invoice.distributionPartnerId()), "Verified Invoice reference required");
    if (actor == null || actor.type() != ActorType.GUEST && actor.type() != ActorType.CUSTOMER) throw denied();
    if (actor.type() == ActorType.CUSTOMER) requireCustomer(actor);
    else if (actor.userId() != null || actor.tenantId() != null) throw denied();
    ComplaintCategories.require(directory.categories(), Kind.INVOICE, category, invoice.tenantId());
    var authority = directory.invoiceAuthorityRoute(invoice);
    if (authority.isPresent()) return authority.get();
    var policy = active(Kind.INVOICE, invoice.tenantId(), category);
    var target = policy.partnerBindings().stream().filter(b -> b.distributionPartnerId().equals(invoice.distributionPartnerId())).findFirst()
        .orElseThrow(() -> invalid("Distribution Partner has no receiving queue"));
    return decision(policy, target.queueId(), null);
  }
  public RoutingDecision platform(Actor actor, String category) {
    requireCustomer(actor);
    var policy = active(Kind.PLATFORM, directory.platformTenantId(), category);
    return decision(policy, policy.platformInitialQueueId(), null);
  }
  public RoutingDecision internal(Actor actor, String category) {
    if (actor == null || actor.type() != ActorType.EMPLOYEE || !actor.permissions().contains(Permission.COMPLAINT_CREATE)
        || !directory.isActiveEmployee(actor.userId(), actor.tenantId()) || !directory.hasPermission(actor.userId(), actor.tenantId(), Permission.COMPLAINT_CREATE)) throw denied();
    var policy = active(Kind.INTERNAL, actor.tenantId(), category);
    var origins = policy.reporterOriginQueueIds().stream().filter(id -> {
      var queue = validator.requireQueue(id);
      return directory.memberships(id).stream().anyMatch(m -> Objects.equals(m.userId(), actor.userId())
          && ComplaintMemberships.eligible(directory, queue, m, QueueCapability.REPORT));
    }).toList();
    require(origins.size() == 1, "Reporter has no unique configured reporting origin");
    return decision(policy, next(policy, origins.get(0)), origins.get(0));
  }
  public RoutingDecision escalate(ComplaintCase complaint, Actor actor, String reason) {
    require(!directory.isInvoiceAuthorityCase(complaint), "Tenant final authority is the configured endpoint for this Invoice route");
    require(text(reason) && reason.strip().length() <= 1000, "Escalation reason is required and must not exceed 1000 characters");
    access.requireEscalate(complaint, actor);
    var matches = directory.policies().stream().filter(p -> Objects.equals(p.id(), complaint.routingPolicyId()) && p.version() == complaint.routingPolicyVersion()).toList();
    require(matches.size() == 1, "Pinned routing policy unavailable or ambiguous");
    var policy = matches.get(0);
    // RETIRED versions remain usable for existing cases; drafts never authorize a transition.
    require(policy.publication() != Publication.DRAFT, "Pinned policy was never published");
    validator.validate(policy);
    String expectedOwner = complaint.kind() == Kind.PLATFORM ? directory.platformTenantId() : complaint.sourceTenantId();
    require(policy.kind() == complaint.kind() && policy.ownerTenantId().equals(expectedOwner)
        && policy.categoryCodes().contains(complaint.categoryCode()) && policy.queueIds().contains(complaint.assignedQueueId()), "Complaint does not match its pinned policy");
    requireReachableCase(policy, complaint);
    var current = validator.requireQueue(complaint.assignedQueueId());
    require(current.scope().equals(complaint.processing()), "Current processing boundary mismatch");
    return decision(policy, next(policy, current.id()), complaint.reporterOriginQueueId());
  }
  private ComplaintRoutingPolicy active(Kind kind, String owner, String category) {
    ComplaintCategories.require(directory.categories(), kind, category, owner);
    var matches = directory.policies().stream().filter(p -> p.active() && p.publication() == Publication.PUBLISHED && p.kind() == kind
        && Objects.equals(owner, p.ownerTenantId()) && p.categoryCodes().contains(category)).toList();
    require(matches.size() == 1, "Active routing policy unavailable or ambiguous");
    var policy = matches.get(0); validator.validate(policy); return policy;
  }
  private String next(ComplaintRoutingPolicy policy, String from) {
    return policy.edges().stream().filter(e -> Objects.equals(from, e.fromQueueId())).map(ComplaintRoutingPolicy.Edge::toQueueId).findFirst()
        .orElseThrow(() -> invalid("No configured next processing level"));
  }
  private void requireReachableCase(ComplaintRoutingPolicy policy, ComplaintCase complaint) {
    String entry;
    switch (complaint.kind()) {
      case INVOICE -> {
        require(complaint.invoice() != null && text(complaint.invoice().invoiceId()) && Objects.equals(complaint.sourceTenantId(), complaint.invoice().tenantId())
            && complaint.reporterOriginQueueId() == null, "Invoice source mismatch");
        entry = policy.partnerBindings().stream().filter(b -> b.distributionPartnerId().equals(complaint.invoice().distributionPartnerId()))
            .map(ComplaintRoutingPolicy.PartnerBinding::queueId).findFirst().orElseThrow(() -> invalid("Invoice partner does not match pinned policy"));
      }
      case PLATFORM -> {
        require(complaint.invoice() == null && complaint.sourceTenantId() == null && complaint.reporterOriginQueueId() == null, "Invalid Platform source");
        entry = policy.platformInitialQueueId();
      }
      case INTERNAL -> {
        require(complaint.invoice() == null && complaint.reporter() != null && complaint.reporter().type() == ActorType.EMPLOYEE
            && Objects.equals(complaint.sourceTenantId(), complaint.reporter().homeTenantId()) && policy.reporterOriginQueueIds().contains(complaint.reporterOriginQueueId()), "Invalid Internal reporting origin");
        // Origin is captured at creation; never infer an old case's origin from today's changed membership.
        entry = next(policy, complaint.reporterOriginQueueId());
      }
      default -> throw invalid("Invalid complaint kind");
    }
    boolean reachable = false;
    for (String current = entry; current != null; ) {
      if (current.equals(complaint.assignedQueueId())) { reachable = true; break; }
      String from = current;
      current = policy.edges().stream().filter(e -> e.fromQueueId().equals(from)).map(ComplaintRoutingPolicy.Edge::toQueueId).findFirst().orElse(null);
    }
    require(reachable, "Current queue is outside the Complaint's original route");
  }
  private RoutingDecision decision(ComplaintRoutingPolicy policy, String target, String origin) {
    var queue = validator.requireQueue(target);
    require(queue.purpose() != QueuePurpose.REPORTING_ORIGIN, "Queue cannot receive complaints");
    return new RoutingDecision(policy.id(), policy.version(), queue.id(), queue.scope(), origin);
  }
  private void requireCustomer(Actor actor) {
    if (actor == null || actor.type() != ActorType.CUSTOMER || !text(actor.userId()) || !text(actor.tenantId())
        || !actor.permissions().contains(Permission.COMPLAINT_CREATE)) throw denied();
  }
  private ForbiddenException denied() { return new ForbiddenException("Complaint creation access required"); }
}
