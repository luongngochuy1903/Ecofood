package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.BadRequestException;
import java.util.*;
import lombok.RequiredArgsConstructor;

/** Pure domain validation against a trusted directory; no roles, RoleLevel or fallback destinations. */
@RequiredArgsConstructor
public class RoutingPolicyValidator {
  private final ComplaintRoutingDirectory directory;

  public void validate(ComplaintRoutingPolicy policy) {
    require(policy != null, "Routing policy required");
    require(text(policy.id()) && policy.version() > 0 && policy.kind() != null && text(policy.ownerTenantId()), "Invalid policy identity");
    require(policy.publication() != null && (policy.publication() == Publication.PUBLISHED || !policy.active()), "Invalid policy lifecycle");
    require(policy.publication() == Publication.DRAFT ? policy.publishedAt() == null && policy.publishedBy() == null
        : policy.publishedAt() != null && text(policy.publishedBy()), "Invalid policy publication metadata");
    require(!policy.categoryCodes().isEmpty(), "Policy categories required");
    policy.categoryCodes().forEach(code -> ComplaintCategories.require(directory.categories(), policy.kind(), code, policy.ownerTenantId()));
    TenantInfo owner = tenant(policy.ownerTenantId());
    TenantInfo platform = tenant(directory.platformTenantId());
    require(platform.platform(), "Platform tenant is not configured");
    require(owner.platform() == (policy.kind() == Kind.PLATFORM), "Policy kind does not match owner boundary");
    TenantInfo master = null;
    if (owner.structure() == TenantStructure.SUB_TENANT) {
      require(text(owner.masterTenantId()) && !owner.id().equals(owner.masterTenantId()), "Sub Tenant requires a unique master");
      master = tenant(owner.masterTenantId());
      require(!master.platform() && master.structure() == TenantStructure.MASTER_TENANT, "Invalid master tenant");
    } else require(owner.masterTenantId() == null, "Unexpected master relationship");
    require(!policy.queueIds().isEmpty() && policy.queueIds().size() <= 128 && policy.edges().size() <= 128, "Invalid policy graph size");
    Map<String, ComplaintQueue> queues = new HashMap<>();
    Map<String, Integer> finalCounts = new HashMap<>();
    for (String id : policy.queueIds()) {
      var queue = requireQueue(id); queues.put(id, queue);
      String queueTenant = queue.scope().tenantId();
      require(queueTenant.equals(owner.id()) || queueTenant.equals(platform.id()) || master != null && queueTenant.equals(master.id()), "Queue outside permitted tenant topology");
      if (policy.kind() == Kind.PLATFORM) require(queue.scope().boundary() == Boundary.PLATFORM, "Platform policy cannot contain Tenant queues");
      if (queue.purpose() == QueuePurpose.FINAL_AUTHORITY) {
        require(finalCounts.merge(queueTenant, 1, Integer::sum) == 1, "Multiple final authorities for one tenant");
        Authority expected = queue.scope().boundary() == Boundary.PLATFORM ? Authority.PLATFORM_AUTHORITY : Authority.TENANT_AUTHORITY;
        require(directory.memberships(id).stream().anyMatch(m -> m.authority() == expected && ComplaintMemberships.eligible(directory, queue, m, QueueCapability.PROCESS)), "Final authority has no eligible handler");
      } else {
        QueueCapability capability = queue.purpose() == QueuePurpose.REPORTING_ORIGIN ? QueueCapability.REPORT : QueueCapability.PROCESS;
        require(directory.memberships(id).stream().anyMatch(m -> ComplaintMemberships.eligible(directory, queue, m, capability)), "Queue has no eligible member");
      }
    }
    Map<String, String> next = new HashMap<>();
    for (var edge : policy.edges()) {
      require(queues.containsKey(edge.fromQueueId()) && queues.containsKey(edge.toQueueId()), "Edge references an unknown queue");
      require(!edge.fromQueueId().equals(edge.toQueueId()), "Self-loop is forbidden");
      require(next.putIfAbsent(edge.fromQueueId(), edge.toQueueId()) == null, "Ambiguous or duplicate outgoing edge");
      validateEdge(owner, queues.get(edge.fromQueueId()), queues.get(edge.toQueueId()));
      var from = queues.get(edge.fromQueueId());
      if (from.purpose() != QueuePurpose.REPORTING_ORIGIN)
        require(directory.memberships(from.id()).stream().anyMatch(m -> ComplaintMemberships.eligible(directory, from, m, QueueCapability.ESCALATE)
            && (from.purpose() != QueuePurpose.FINAL_AUTHORITY || m.authority() != Authority.NONE)), "Queue has no eligible escalator");
    }
    for (String start : queues.keySet()) {
      var visited = new HashSet<String>(); String current = start;
      while (current != null) {
        require(visited.add(current), "Routing cycle is forbidden"); current = next.get(current);
      }
      if (!next.containsKey(start)) require(queues.get(start).purpose() == QueuePurpose.FINAL_AUTHORITY, "Non-final queue cannot be terminal");
    }
    Set<String> entries = entries(policy, queues, owner, next);
    var reachable = new HashSet<String>();
    for (String entry : entries) for (String current = entry; current != null; current = next.get(current)) reachable.add(current);
    require(reachable.equals(queues.keySet()), "Policy contains unreachable queues");
  }

  public ComplaintQueue requireQueue(String id) {
    require(text(id), "Queue identifier required");
    var queue = directory.queue(id).orElseThrow(() -> invalid("Queue unavailable"));
    require(id.equals(queue.id()) && text(queue.name()) && queue.active() && queue.scope() != null
        && queue.scope().boundary() != null && queue.purpose() != null, "Invalid or inactive queue");
    var owner = tenant(queue.scope().tenantId());
    require(owner.platform() == (queue.scope().boundary() == Boundary.PLATFORM), "Queue boundary mismatch");
    if (owner.platform()) require(owner.id().equals(directory.platformTenantId()), "Unknown Platform boundary");
    return queue;
  }

  private Set<String> entries(ComplaintRoutingPolicy p, Map<String, ComplaintQueue> queues, TenantInfo owner, Map<String, String> next) {
    Set<String> entries = new HashSet<>();
    switch (p.kind()) {
      case INVOICE -> {
        require(p.platformInitialQueueId() == null && p.reporterOriginQueueIds().isEmpty() && !p.partnerBindings().isEmpty(), "Invalid Invoice entry configuration");
        Set<String> partners = new HashSet<>();
        for (var binding : p.partnerBindings()) {
          require(text(binding.distributionPartnerId()) && partners.add(binding.distributionPartnerId()), "Ambiguous Distribution Partner binding");
          var queue = queues.get(binding.queueId());
          require(queue != null && queue.scope().tenantId().equals(owner.id()) && queue.purpose() != QueuePurpose.REPORTING_ORIGIN, "Invoice initial queue must belong to its seller Tenant");
          entries.add(binding.queueId());
        }
      }
      case PLATFORM -> {
        require(p.partnerBindings().isEmpty() && p.reporterOriginQueueIds().isEmpty(), "Invalid Platform entry configuration");
        var queue = queues.get(p.platformInitialQueueId());
        require(queue != null && queue.scope().boundary() == Boundary.PLATFORM && queue.purpose() != QueuePurpose.REPORTING_ORIGIN, "Platform support queue required");
        entries.add(queue.id());
      }
      case INTERNAL -> {
        require(p.platformInitialQueueId() == null && p.partnerBindings().isEmpty() && !p.reporterOriginQueueIds().isEmpty(), "Internal reporter origins required");
        Set<String> reporters = new HashSet<>();
        for (String origin : p.reporterOriginQueueIds()) {
          var queue = queues.get(origin);
          require(queue != null && queue.scope().tenantId().equals(owner.id()) && next.containsKey(origin), "Internal origin must have a next processing level");
          require(directory.memberships(origin).stream().anyMatch(m -> ComplaintMemberships.eligible(directory, queue, m, QueueCapability.REPORT)), "Internal origin has no eligible reporter");
          for (var member : directory.memberships(origin)) if (ComplaintMemberships.eligible(directory, queue, member, QueueCapability.REPORT))
            require(reporters.add(member.userId()), "Reporter belongs to ambiguous reporting origins");
          entries.add(origin);
        }
      }
    }
    return entries;
  }

  private void validateEdge(TenantInfo owner, ComplaintQueue from, ComplaintQueue to) {
    require(to.purpose() != QueuePurpose.REPORTING_ORIGIN, "Cannot route to a reporting-only queue");
    if (from.scope().tenantId().equals(to.scope().tenantId())) {
      require(from.purpose() != QueuePurpose.FINAL_AUTHORITY, "Final authority must be last within its tenant"); return;
    }
    require(from.purpose() == QueuePurpose.FINAL_AUTHORITY
        && (to.scope().boundary() == Boundary.PLATFORM || to.purpose() == QueuePurpose.FINAL_AUTHORITY), "Cross-boundary handoff requires the configured final authority");
    if (to.scope().boundary() == Boundary.PLATFORM) {
      String expected = owner.structure() == TenantStructure.SUB_TENANT ? owner.masterTenantId() : owner.id();
      require(from.scope().boundary() == Boundary.TENANT && from.scope().tenantId().equals(expected), "Platform handoff skips required final authority");
    } else require(owner.structure() == TenantStructure.SUB_TENANT && from.scope().tenantId().equals(owner.id())
        && to.scope().tenantId().equals(owner.masterTenantId()), "Invalid cross-tenant escalation");
  }
  private TenantInfo tenant(String id) {
    require(text(id), "Tenant identifier required");
    var tenant = directory.tenant(id).orElseThrow(() -> invalid("Tenant unavailable"));
    require(id.equals(tenant.id()) && tenant.active() && tenant.structure() != null, "Invalid or inactive tenant"); return tenant;
  }
  static boolean text(String value) { return value != null && !value.isBlank(); }
  static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
  static BadRequestException invalid(String message) { return new BadRequestException(message); }
}
