package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import java.time.*;
import java.util.*;

/** Test-only trusted directory. Production adapters are intentionally deferred to Complaint Core. */
class ComplaintFixture implements ComplaintRoutingDirectory {
  static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final String T = "seller", M = "master", P = "platform", PORTAL = "customer-portal";
  final Map<String, TenantInfo> tenants = new HashMap<>();
  final Map<String, ComplaintQueue> queues = new HashMap<>();
  final Map<String, List<ComplaintQueueMembership>> members = new HashMap<>();
  final Map<String, Set<Permission>> permissions = new HashMap<>();
  final Set<String> employees = new HashSet<>(), deniedScopes = new HashSet<>(), deniedOperations = new HashSet<>();
  final List<ComplaintCategory> categories = new ArrayList<>(ComplaintCategories.suggestedDefaults());
  final List<ComplaintRoutingPolicy> policies = new ArrayList<>();
  final RoutingPolicyValidator validator = new RoutingPolicyValidator(this);
  final ComplaintAccessPolicy access = new ComplaintAccessPolicy(this, CLOCK);
  final RoutingPolicyResolver resolver = new RoutingPolicyResolver(this, validator, access);
  final RoutingPolicyPublisher publisher = new RoutingPolicyPublisher(this, validator, CLOCK);
  ComplaintFixture() {
    tenants.put(T, new TenantInfo(T, TenantStructure.STANDALONE, null, false, true));
    tenants.put(M, new TenantInfo(M, TenantStructure.MASTER_TENANT, null, false, true));
    tenants.put(P, new TenantInfo(P, TenantStructure.STANDALONE, null, true, true));
    categories.add(new ComplaintCategory("TEAM", Kind.INTERNAL, T, "Team Issue", true));
    addQueue("origin", T, QueuePurpose.REPORTING_ORIGIN); addQueue("review", T, QueuePurpose.PROCESSING);
    addQueue("final", T, QueuePurpose.FINAL_AUTHORITY); addQueue("master-final", M, QueuePurpose.FINAL_AUTHORITY); addQueue("support", P, QueuePurpose.FINAL_AUTHORITY);
    member("origin", "reporter", Authority.NONE, Set.of(QueueCapability.REPORT));
    member("review", "handler", Authority.NONE, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ASSIGN, QueueCapability.ESCALATE));
    member("final", "authority", Authority.TENANT_AUTHORITY, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ASSIGN, QueueCapability.ESCALATE));
    member("master-final", "master-handler", Authority.TENANT_AUTHORITY, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ESCALATE));
    member("support", "platform-handler", Authority.PLATFORM_AUTHORITY, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ESCALATE));
    grant("publisher", T);
  }
  void addQueue(String id, String tenant, QueuePurpose purpose) {
    queues.put(id, new ComplaintQueue(id, id, new ProcessingScope(tenant.equals(P) ? Boundary.PLATFORM : Boundary.TENANT, tenant), purpose, true));
  }
  void grant(String user, String tenant) { employees.add(tenant + ":" + user); permissions.put(tenant + ":" + user, EnumSet.allOf(Permission.class)); }
  void member(String queueId, String user, Authority authority, Set<QueueCapability> capabilities) {
    grant(user, queues.get(queueId).scope().tenantId());
    members.computeIfAbsent(queueId, key -> new ArrayList<>()).add(new ComplaintQueueMembership(queueId + ":" + user, queueId, user, true, capabilities, authority));
  }
  Actor employee(String user, String tenant) { return new Actor(ActorType.EMPLOYEE, user, tenant, EnumSet.allOf(Permission.class)); }
  Actor customer(String id) { return new Actor(ActorType.CUSTOMER, id, PORTAL, Set.of(Permission.COMPLAINT_CREATE, Permission.COMPLAINT_READ)); }
  Actor guest() { return new Actor(ActorType.GUEST, null, null, Set.of()); }
  ComplaintRoutingPolicy internal() {
    return draft("internal", T, Kind.INTERNAL, "TEAM", Set.of("origin", "review", "final", "support"),
        List.of(edge("origin", "review"), edge("review", "final"), edge("final", "support")), Set.of("origin"), List.of(), null);
  }
  ComplaintRoutingPolicy invoice() {
    return draft("invoice", T, Kind.INVOICE, "PRODUCT_ISSUE", Set.of("review", "final"), List.of(edge("review", "final")), Set.of(),
        List.of(new ComplaintRoutingPolicy.PartnerBinding("SHOP-A", "review")), null);
  }
  ComplaintRoutingPolicy platform() { return draft("platform", P, Kind.PLATFORM, "LOGIN", Set.of("support"), List.of(), Set.of(), List.of(), "support"); }
  ComplaintRoutingPolicy draft(String id, String owner, Kind kind, String category, Set<String> nodes, List<ComplaintRoutingPolicy.Edge> edges,
      Set<String> origins, List<ComplaintRoutingPolicy.PartnerBinding> bindings, String initial) {
    return new ComplaintRoutingPolicy(id, 1, owner, kind, Set.of(category), nodes, edges, origins, bindings, initial, Publication.DRAFT, false, null, null);
  }
  ComplaintRoutingPolicy graph(ComplaintRoutingPolicy p, Set<String> nodes, List<ComplaintRoutingPolicy.Edge> edges) {
    return new ComplaintRoutingPolicy(p.id(), p.version(), p.ownerTenantId(), p.kind(), p.categoryCodes(), nodes, edges, p.reporterOriginQueueIds(),
        p.partnerBindings(), p.platformInitialQueueId(), p.publication(), p.active(), p.publishedAt(), p.publishedBy());
  }
  ComplaintRoutingPolicy published(ComplaintRoutingPolicy p) {
    return new ComplaintRoutingPolicy(p.id(), p.version(), p.ownerTenantId(), p.kind(), p.categoryCodes(), p.queueIds(), p.edges(), p.reporterOriginQueueIds(),
        p.partnerBindings(), p.platformInitialQueueId(), Publication.PUBLISHED, true, NOW, "publisher");
  }
  ComplaintCase at(ComplaintRoutingPolicy p, String queue, Status status, Actor reporter, String assignee) {
    return new ComplaintCase("case-1", "CMP-1", p.kind(), p.categoryCodes().iterator().next(), "Subject", "Description",
        new Reporter(reporter.type(), reporter.userId(), reporter.tenantId()), p.kind() == Kind.INVOICE ? new InvoiceReference("invoice-1", T, "SHOP-A") : null,
        p.kind() == Kind.PLATFORM ? null : T, p.kind() == Kind.INTERNAL ? "origin" : null, queues.get(queue).scope(), queue, assignee, p.id(), p.version(), status, 7,
        new Audit(NOW, reporter.userId(), NOW, reporter.userId()));
  }
  static ComplaintRoutingPolicy.Edge edge(String from, String to) { return new ComplaintRoutingPolicy.Edge(from, to); }
  public Optional<TenantInfo> tenant(String id) { return Optional.ofNullable(tenants.get(id)); }
  public String platformTenantId() { return P; }
  public Optional<ComplaintQueue> queue(String id) { return Optional.ofNullable(queues.get(id)); }
  public List<ComplaintQueueMembership> memberships(String id) { return members.getOrDefault(id, List.of()); }
  public List<ComplaintCategory> categories() { return categories; }
  public List<ComplaintRoutingPolicy> policies() { return policies; }
  public boolean isActiveEmployee(String user, String tenant) { return employees.contains(tenant + ":" + user); }
  public boolean hasPermission(String user, String tenant, Permission permission) { return permissions.getOrDefault(tenant + ":" + user, Set.of()).contains(permission); }
  public boolean inDataScope(Actor actor, ComplaintCase complaint, Permission operation) {
    return !deniedScopes.contains(actor.userId()) && !deniedOperations.contains(actor.userId() + ":" + operation);
  }
}
