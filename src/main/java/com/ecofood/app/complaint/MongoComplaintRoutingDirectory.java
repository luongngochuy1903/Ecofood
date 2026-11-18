package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintDirectorySources.*;
import com.ecofood.app.complaint.ComplaintRoutingCatalog.Configuration;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component @RequiredArgsConstructor
public class MongoComplaintRoutingDirectory implements ComplaintRoutingDirectory {
  private final ComplaintMongoStore store;
  private final Tenants tenants;
  private final Employees employees;
  private final ThreadLocal<Snapshot> current = new ThreadLocal<>();
  private static class Snapshot { Configuration configuration; final Map<String, EmployeeFacts> employees = new HashMap<>(); final Map<String, Optional<TenantInfo>> tenants = new HashMap<>(); final Map<String,String> labels = new HashMap<>(); final Map<String, Set<String>> authorities = new HashMap<>(); }
  private Set<String> authorities(String tenant) {
    var snapshot = current.get(); return snapshot == null ? employees.finalAuthorities(tenant) : snapshot.authorities.computeIfAbsent(tenant, employees::finalAuthorities);
  }
  public <T> T inSnapshot(Supplier<T> action) {
    if (current.get() != null) return action.get(); current.set(new Snapshot());
    try { return action.get(); } finally { current.remove(); }
  }
  <T> T withConfiguration(Configuration configuration, Supplier<T> action) {
    var previous = current.get(); var next = new Snapshot(); next.configuration = configuration; current.set(next);
    try { return action.get(); } finally { if (previous == null) current.remove(); else current.set(previous); }
  }
  private Configuration configuration() {
    var snapshot = current.get(); if (snapshot != null && snapshot.configuration != null) return snapshot.configuration;
    var row = store.template().findById("active", ComplaintRoutingCatalog.class);
    if (row == null) {
      if (!employees.invoiceAuthorityRouting()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Complaint routing is not configured");
      var empty = new Configuration(0, null, List.of(), List.of(), List.of(), List.of(), List.of());
      if (snapshot != null) snapshot.configuration = empty;
      return empty;
    }
    if (snapshot != null) snapshot.configuration = row.configuration(); return row.configuration();
  }
  public Optional<TenantInfo> tenant(String id) {
    var snapshot = current.get(); return snapshot == null ? tenants.find(id) : snapshot.tenants.computeIfAbsent(id, tenants::find);
  }
  public String platformTenantId() { return tenants.platformId(); }
  public Optional<ComplaintQueue> queue(String id) {
    String tenant = InvoiceAuthorityRoute.tenant(id);
    if (tenant != null && employees.invoiceAuthorityRouting()) return tenant(tenant).filter(TenantInfo::active).filter(t -> !t.platform())
        .map(t -> new ComplaintQueue(id, "Quản trị Tenant xuất hóa đơn", new ProcessingScope(Boundary.TENANT, tenant), QueuePurpose.FINAL_AUTHORITY, true));
    return configuration().queues().stream().filter(q -> q.id().equals(id)).findFirst();
  }
  public List<ComplaintQueueMembership> memberships(String id) {
    String tenant = InvoiceAuthorityRoute.tenant(id);
    if (tenant != null && employees.invoiceAuthorityRouting()) return authorities(tenant).stream().sorted()
        .map(user -> new ComplaintQueueMembership(id + ":" + user, id, user, true,
            Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ASSIGN), Authority.TENANT_AUTHORITY)).toList();
    return configuration().memberships().stream().filter(m -> m.queueId().equals(id)).toList();
  }
  public List<ComplaintCategory> categories() {
    var result = new ArrayList<>(configuration().categories());
    if (employees.invoiceAuthorityRouting()) for (var category : ComplaintCategories.suggestedDefaults())
      if (category.kind() == Kind.INVOICE && result.stream().noneMatch(c -> c.kind() == Kind.INVOICE && c.code().equals(category.code()) && c.ownerTenantId() == null)) result.add(category);
    return List.copyOf(result);
  }
  public List<ComplaintRoutingPolicy> policies() { return configuration().policies(); }
  public Optional<RoutingDecision> invoiceAuthorityRoute(VerifiedInvoice invoice) {
    if (!employees.invoiceAuthorityRouting()) return Optional.empty();
    var queue = queue(InvoiceAuthorityRoute.queue(invoice.tenantId())).orElseThrow(() -> new com.ecofood.app.config.BadRequestException("Invoice seller Tenant unavailable"));
    return Optional.of(new RoutingDecision(InvoiceAuthorityRoute.POLICY, 1, queue.id(), queue.scope(), null));
  }
  public boolean isInvoiceAuthorityCase(ComplaintCase c) {
    return employees.invoiceAuthorityRouting() && c != null && c.kind() == Kind.INVOICE && c.invoice() != null
        && InvoiceAuthorityRoute.POLICY.equals(c.routingPolicyId()) && c.routingPolicyVersion() == 1
        && Objects.equals(c.sourceTenantId(), c.invoice().tenantId())
        && Objects.equals(c.processing(), new ProcessingScope(Boundary.TENANT, c.invoice().tenantId()))
        && Objects.equals(c.assignedQueueId(), InvoiceAuthorityRoute.queue(c.invoice().tenantId()))
        && queue(c.assignedQueueId()).isPresent();
  }
  public String employeeLabel(String user, String tenant) { var snapshot = current.get(); return snapshot == null ? employees.label(user, tenant) : snapshot.labels.computeIfAbsent(tenant + ":" + user, ignored -> employees.label(user, tenant)); }
  /** Query predicates are built before MongoDB paging/counting, never by filtering a global result. */
  public ComplaintCoreContracts.InboxScope inboxScope(Actor actor) {
    if (actor == null || actor.type() != ActorType.EMPLOYEE || !actor.permissions().contains(Permission.COMPLAINT_READ)
        || !hasPermission(actor.userId(), actor.tenantId(), Permission.COMPLAINT_READ)) throw new com.ecofood.app.config.ForbiddenException("Processing access required");
    var tenant = tenant(actor.tenantId()).filter(TenantInfo::active).orElseThrow(() -> new com.ecofood.app.config.ForbiddenException("Tenant unavailable"));
    var scope = new ProcessingScope(tenant.platform() ? Boundary.PLATFORM : Boundary.TENANT, tenant.id());
    var grants = new ArrayList<ComplaintCoreContracts.InboxGrant>();
    if (employees.invoiceAuthorityRouting() && !tenant.platform() && authorities(tenant.id()).contains(actor.userId()))
      grants.add(new ComplaintCoreContracts.InboxGrant(InvoiceAuthorityRoute.queue(tenant.id()), Kind.INVOICE, tenant.id(), true, Set.of()));
    for (var grant : configuration().grants()) {
      if (!grant.userId().equals(actor.userId())) continue;
      var queue = queue(grant.queueId()).orElse(null);
      if (queue == null || !queue.active() || !scope.equals(queue.scope()) || queue.purpose() == QueuePurpose.REPORTING_ORIGIN
          || memberships(queue.id()).stream().noneMatch(m -> m.userId().equals(actor.userId()) && ComplaintMemberships.eligible(this, queue, m, QueueCapability.READ))) continue;
      if (grant.kinds().contains(Kind.INVOICE)) for (String source : grant.sourceTenantIds()) {
        if (employees.invoiceAuthorityRouting() && source.equals(tenant.id()) && !authorities(tenant.id()).contains(actor.userId())) continue;
        grants.add(new ComplaintCoreContracts.InboxGrant(queue.id(), Kind.INVOICE, source, grant.allInvoicesWithinSource(), grant.invoiceIds()));
      }
      if (grant.kinds().contains(Kind.PLATFORM) && grant.platformSource() && tenant.platform())
        grants.add(new ComplaintCoreContracts.InboxGrant(queue.id(), Kind.PLATFORM, null, true, Set.of()));
    }
    return new ComplaintCoreContracts.InboxScope(scope, grants);
  }
  private EmployeeFacts employee(String user, String tenant) {
    var snapshot = current.get(); return snapshot == null ? employees.resolve(user, tenant) : snapshot.employees.computeIfAbsent(tenant + ":" + user, key -> employees.resolve(user, tenant));
  }
  public boolean isActiveEmployee(String user, String tenant) { return tenant(tenant).filter(TenantInfo::active).isPresent() && employee(user, tenant).active(); }
  public boolean hasPermission(String user, String tenant, Permission permission) { return isActiveEmployee(user, tenant) && employee(user, tenant).permissions().contains(permission); }
  public boolean inDataScope(Actor actor, ComplaintCase complaint, Permission operation) {
    if (actor == null || actor.type() != ActorType.EMPLOYEE || complaint.processing() == null || !Objects.equals(actor.tenantId(), complaint.processing().tenantId())) return false;
    if (employees.invoiceAuthorityRouting() && complaint.kind() == Kind.INVOICE && Objects.equals(actor.tenantId(), complaint.sourceTenantId())
        && !authorities(actor.tenantId()).contains(actor.userId())) return false;
    if (isInvoiceAuthorityCase(complaint)) return authorities(actor.tenantId()).contains(actor.userId()) && hasPermission(actor.userId(), actor.tenantId(), operation);
    String source = complaint.kind() == Kind.PLATFORM ? null : complaint.sourceTenantId();
    return configuration().grants().stream().anyMatch(grant -> grant.userId().equals(actor.userId()) && grant.queueId().equals(complaint.assignedQueueId())
        && grant.kinds().contains(complaint.kind()) && (source == null ? grant.platformSource() : grant.sourceTenantIds().contains(source))
        && (complaint.kind() != Kind.INVOICE || grant.allInvoicesWithinSource() || complaint.invoice() != null && grant.invoiceIds().contains(complaint.invoice().invoiceId())));
  }
}
