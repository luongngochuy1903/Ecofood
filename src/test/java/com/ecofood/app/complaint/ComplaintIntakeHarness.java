package com.ecofood.app.complaint;

import static org.mockito.Mockito.*;
import static com.ecofood.app.complaint.ComplaintCoreFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintDirectorySources.*;
import com.ecofood.app.complaint.ComplaintRoutingCatalog.*;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.customer.repository.CustomerPurchaseRepository;
import com.ecofood.app.receipt.service.ReceiptService;
import com.ecofood.app.receipt.entity.ReceiptAccess;
import com.ecofood.app.userACL.entity.UserEntity;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** Real catalog/resolver/access/core/projection/proof/limiter. Only external identity/receipt ports are mocked. */
class ComplaintIntakeHarness {
  final ComplaintCoreMongoIT db;
  final CustomerIdentityService customers = mock(CustomerIdentityService.class);
  final CustomerPurchaseRepository purchases = mock(CustomerPurchaseRepository.class);
  final ReceiptService receipts = mock(ReceiptService.class);
  final MongoComplaintRoutingDirectory directory;
  final ComplaintRoutingCatalogImporter importer;
  final GuestComplaintAccessService proof;
  final ComplaintPublicRateLimiter rates;
  final ComplaintIntakeService intake;
  final Map<String, TenantInfo> tenantOverrides = new HashMap<>();
  final Map<String, EmployeeFacts> employeeOverrides = new HashMap<>();
  final ComplaintWorkflowService workflow;
  final ComplaintHistoryService history;
  final ComplaintAccessPolicy access;
  final RoutingPolicyResolver routing;
  final String token = "a".repeat(43), receiptId = "000000000000000000000051";
  Configuration config;
  final Set<String> finalAuthorityUsers = new HashSet<>(Set.of(AUTHORITY));
  ComplaintIntakeHarness(ComplaintCoreMongoIT db) { this(db, false); }
  ComplaintIntakeHarness(ComplaintCoreMongoIT db, boolean sourceRouting) {
    this.db = db;
    Tenants tenants = new Tenants() {
      public Optional<TenantInfo> find(String id) { return tenantOverrides.containsKey(id) ? Optional.of(tenantOverrides.get(id)) : db.f.tenant(id); }
      public String platformId() { return PLATFORM; }
    };
    Employees employees = new Employees() {
      public EmployeeFacts resolve(String u, String t) { return employeeOverrides.getOrDefault(t + ":" + u, new EmployeeFacts(db.f.isActiveEmployee(u, t), db.f.isActiveEmployee(u, t) ? EnumSet.allOf(Permission.class) : Set.of())); }
      public boolean invoiceAuthorityRouting() { return sourceRouting; }
      public Set<String> finalAuthorities(String t) { return SELLER.equals(t) ? finalAuthorityUsers.stream().filter(u -> resolve(u, t).active()).collect(java.util.stream.Collectors.toSet()) : Set.of(); }
    };
    directory = new MongoComplaintRoutingDirectory(db.store, tenants, employees);
    var validator = new RoutingPolicyValidator(directory); access = new ComplaintAccessPolicy(directory, db.f.clock);
    var beans = new StaticListableBeanFactory(); beans.addBean("access", access);
    var auth = new ComplaintAuthorization(beans.getBeanProvider(ComplaintAccessPolicy.class));
    workflow = new ComplaintWorkflowService(db.complaints, db.events, new ComplaintValidation(db.f.invoices), auth, db.projection, db.f.clock);
    history = new ComplaintHistoryService(db.complaints, db.events, auth);
    routing = new RoutingPolicyResolver(directory, validator, access);
    var secrets = new ComplaintAccessSecrets(); ReflectionTestUtils.setField(secrets, "secret", Base64.getEncoder().encodeToString(new byte[32]));
    proof = new GuestComplaintAccessService(db.store, secrets, db.f.clock); ReflectionTestUtils.setField(proof, "ttl", Duration.ofDays(7)); ReflectionTestUtils.setField(proof, "baseUrl", "http://portal.test/complaint");
    rates = new ComplaintPublicRateLimiter(db.store, secrets, db.f.clock);
    intake = new ComplaintIntakeService(directory, routing, workflow, db.complaints, history, customers, purchases, receipts, proof, rates);
    importer = new ComplaintRoutingCatalogImporter(db.store, directory, validator, db.f.clock);
    var origin = new ComplaintQueue("origin", "Reporting origin", new ProcessingScope(Boundary.TENANT, SELLER), QueuePurpose.REPORTING_ORIGIN, true);
    var queues = List.of(origin, db.f.queue("review").orElseThrow(), db.f.queue("final").orElseThrow(), db.f.queue("platform-support").orElseThrow());
    var members = new ArrayList<ComplaintQueueMembership>();
    members.add(new ComplaintQueueMembership("origin-reporter", "origin", EMPLOYEE, true, Set.of(QueueCapability.REPORT), Authority.NONE));
    for (String q : List.of("review", "final", "platform-support")) members.addAll(db.f.memberships(q));
    var categories = new ArrayList<>(ComplaintCategories.suggestedDefaults()); categories.add(new ComplaintCategory("TEAM", Kind.INTERNAL, SELLER, "Team issue", true));
    var invoice = policy("intake-invoice", SELLER, Kind.INVOICE, Set.of("PRODUCT_ISSUE", "SERVICE_ISSUE"), Set.of("review", "final"), List.of(new ComplaintRoutingPolicy.Edge("review", "final")), Set.of(), List.of(new ComplaintRoutingPolicy.PartnerBinding("SHOP-OPAQUE", "review")), null, AUTHORITY);
    var platform = policy("intake-platform", PLATFORM, Kind.PLATFORM, Set.of("SYSTEM_ERROR"), Set.of("platform-support"), List.of(), Set.of(), List.of(), "platform-support", PLATFORM_HANDLER);
    var internal = policy("intake-internal", SELLER, Kind.INTERNAL, Set.of("TEAM"), Set.of("origin", "review", "final"), List.of(new ComplaintRoutingPolicy.Edge("origin", "review"), new ComplaintRoutingPolicy.Edge("review", "final")), Set.of("origin"), List.of(), null, AUTHORITY);
    config = new Configuration(1, PLATFORM_HANDLER, categories, queues, members, List.of(invoice, platform, internal),
        List.of(new ScopeGrant(HANDLER, "review", Set.of(Kind.INVOICE, Kind.INTERNAL), Set.of(SELLER), false, true, Set.of()),
            new ScopeGrant(AUTHORITY, "final", Set.of(Kind.INVOICE, Kind.INTERNAL), Set.of(SELLER), false, true, Set.of()),
            new ScopeGrant(PLATFORM_HANDLER, "platform-support", Set.of(Kind.PLATFORM), Set.of(), true, false, Set.of())));
    if (!sourceRouting) importer.install(config);
    var receipt = new ReceiptAccess(); receipt.setId(receiptId);
    when(receipts.requireValidReceipt(token)).thenReturn(new ReceiptService.ValidatedReceipt(db.f.invoice, receipt));
    when(purchases.detail(new CustomerIdentityService.CustomerSession(CUSTOMER, PORTAL), INVOICE)).thenReturn(Optional.of(db.f.invoice));
    var customer = new UserEntity(); customer.setId(new org.bson.types.ObjectId(CUSTOMER));
    when(customers.requireSelfServiceAccount(anyString())).thenReturn(customer);
  }
  ComplaintRoutingPolicy policy(String id, String owner, Kind kind, Set<String> cats, Set<String> queues, List<ComplaintRoutingPolicy.Edge> edges,
      Set<String> origins, List<ComplaintRoutingPolicy.PartnerBinding> bindings, String initial, String publisher) {
    return new ComplaintRoutingPolicy(id, 1, owner, kind, cats, queues, edges, origins, bindings, initial, Publication.PUBLISHED, true, db.f.clock.instant(), publisher);
  }
}
