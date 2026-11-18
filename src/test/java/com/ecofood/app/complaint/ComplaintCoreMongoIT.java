package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ecofood.app.complaint.ComplaintCoreFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.config.*;
import com.ecofood.app.notification.*;
import com.ecofood.app.customer.auth.*;
import com.ecofood.app.customer.repository.*;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.transaction.entity.Invoice;
import com.mongodb.*;
import com.mongodb.client.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;

/** Opt-in real MongoDB test. All operations are redirected to private UUID-owned collections. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComplaintCoreMongoIT {
  MongoClient client; MongoDatabase actual; Map<String, String> namespaces;
  ComplaintCoreFixture f; MongoTemplate mongo; ComplaintMongoStore store;
  ComplaintRepository complaints; ComplaintEventRepository events; ComplaintOutboxRepository outbox;
  ComplaintProjectionService projection; ComplaintWorkflowService workflow; ComplaintHistoryService history;
  Document originalInvoice, originalLink;

  @BeforeAll void connect() throws Exception {
    var properties = new Properties();
    if (Files.exists(Path.of(".env"))) try (var reader = Files.newBufferedReader(Path.of(".env"))) { properties.load(reader); }
    String uri = System.getenv("MONGODB_URI"); if (uri == null || uri.isBlank()) uri = properties.getProperty("MONGODB_URI");
    assertNotNull(uri, "MongoDB URI required for opt-in integration test");
    String database = System.getenv("MONGODB_DATABASE"); if (database == null || database.isBlank()) database = properties.getProperty("MONGODB_DATABASE", "appdb");
    var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
        .applyToClusterSettings(builder -> builder.serverSelectionTimeout(10, TimeUnit.SECONDS)).build();
    client = MongoClients.create(settings);
    actual = client.getDatabase(database).withReadConcern(ReadConcern.MAJORITY).withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
  }
  @BeforeEach void isolate() throws Exception {
    f = new ComplaintCoreFixture(); namespaces = new LinkedHashMap<>(); String suffix = UUID.randomUUID().toString().replace("-", "");
    for (String name : List.of("complaint", "complaint_event", "complaint_outbox", "invoice", "customer_invoice_link", "complaint_routing_catalog", "complaint_guest_access", "complaint_intake_rate", "notification", "notification_preferences")) namespaces.put(name, "complaint_core_test_" + name + "_" + suffix);
    var existing = actual.listCollectionNames().into(new ArrayList<String>());
    assertTrue(namespaces.values().stream().noneMatch(existing::contains));
    var isolatedDb = (MongoDatabase) java.lang.reflect.Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(), new Class<?>[]{MongoDatabase.class}, (proxy, method, args) -> {
      if (method.getName().equals("drop")) throw new SecurityException("Database drop forbidden");
      if (method.getName().equals("getCollection")) {
        var mapped = args.clone(); mapped[0] = namespaces.get((String) args[0]); if (mapped[0] == null) throw new SecurityException("Unexpected collection");
        return method.invoke(actual, mapped);
      }
      return method.invoke(actual, args);
    });
    var base = new SimpleMongoClientDatabaseFactory(client, actual.getName());
    var factory = (org.springframework.data.mongodb.MongoDatabaseFactory) java.lang.reflect.Proxy.newProxyInstance(
        org.springframework.data.mongodb.MongoDatabaseFactory.class.getClassLoader(), new Class<?>[]{org.springframework.data.mongodb.MongoDatabaseFactory.class}, (proxy, method, args) -> {
          if (method.getName().equals("getMongoDatabase")) return isolatedDb; return method.invoke(base, args);
        });
    mongo = new MongoTemplate(factory); mongo.setWriteConcern(WriteConcern.MAJORITY.withJournal(true)); store = new ComplaintMongoStore(mongo);
    new ComplaintMongoConfig().complaintCoreIndexes(store).run(null);
    mongo.insert(f.invoice); when(f.invoices.findById(INVOICE)).thenAnswer(call -> Optional.ofNullable(mongo.findById(INVOICE, Invoice.class)));
    originalInvoice = mongo.findById(new ObjectId(INVOICE), Document.class, "invoice");
    originalLink = new Document("_id", new ObjectId()).append("invoice_identifier", INVOICE).append("customer_identifier", CUSTOMER).append("tenant_identifier", SELLER);
    mongo.insert(originalLink, "customer_invoice_link");
    complaints = spy(new ComplaintRepository(store)); events = spy(new ComplaintEventRepository(store)); outbox = spy(new ComplaintOutboxRepository(store, f.clock));
    services();
  }
  void services() {
    projection = new ComplaintProjectionService(complaints, events, outbox, f.clock);
    workflow = new ComplaintWorkflowService(complaints, events, new ComplaintValidation(f.invoices), f.authorization, projection, f.clock);
    history = new ComplaintHistoryService(complaints, events, f.authorization);
  }
  @AfterEach void cleanup() {
    if (namespaces != null) for (String name : namespaces.values()) {
      assertTrue(name.matches("complaint_core_test_[a-z_]+_[a-f0-9]{32}"), "Unsafe cleanup target"); actual.getCollection(name).drop();
    }
    System.out.println("COMPLAINT_CORE_MONGO CLEANUP: UUID-owned test collections removed");
  }
  @AfterAll void disconnect() { if (client != null) client.close(); }
  long count(Class<?> type) { return mongo.count(new Query(), type); }
  Complaint get(CommandResult result) { return complaints.findCommitted(result.complaintId()).orElseThrow(); }
  Actor processor(Kind kind) { return kind == Kind.PLATFORM ? f.actor(PLATFORM_HANDLER, PLATFORM) : f.handler(); }

  class NotificationHarness {
    final CustomerIdentityService identities = mock(CustomerIdentityService.class);
    final NotificationRepository notes;
    final NotificationService service;
    final ComplaintNotificationSource source = new ComplaintNotificationSource(complaints, events, outbox);
    final ComplaintNotificationWorker worker;
    NotificationHarness() throws Exception {
      var factory = new org.springframework.data.mongodb.repository.support.MongoRepositoryFactory(mongo);
      notes = factory.getRepository(NotificationRepository.class);
      var preferences = factory.getRepository(NotificationPreferenceRepository.class);
      new NotificationMongoConfig().notificationIndexes(mongo).run(null);
      var portal = mock(CustomerTenantResolver.class);
      when(portal.resolve()).thenReturn(new CustomerTenantResolver.Portal(PORTAL, "Portal", "portal", "client", "https://identity.test/realms/portal"));
      when(identities.requireAccount(anyString())).thenAnswer(call -> {
        var user = UserEntity.builder().partyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(PORTAL)).build())).build();
        user.setId(new ObjectId((String) call.getArgument(0))); return user;
      });
      when(identities.requirePurchaseSession()).thenReturn(new CustomerIdentityService.CustomerSession(CUSTOMER, PORTAL));
      when(identities.requireSelfServiceAccount("CUSTOMER_NOTIFICATION_MANAGE")).thenAnswer(call -> identities.requireAccount(identities.requirePurchaseSession().customerId()));
      service = spy(new NotificationService(identities, portal, mock(CustomerPurchaseRepository.class), new NotificationPreferenceService(identities, preferences, f.clock), notes, mongo, f.clock, source));
      worker = new ComplaintNotificationWorker(outbox, source, service);
    }
  }

  @Test void notificationSubmittedUsesRealUC016PersistenceForCustomerOnlyAndPreservesTenantIsolation() throws Exception {
    var n = new NotificationHarness();
    var invoice = workflow.submit(f.submit(Kind.INVOICE, "notice-invoice"));
    var platform = workflow.submit(f.submit(Kind.PLATFORM, "notice-platform"));
    workflow.submit(f.submit(Kind.INVOICE, "notice-guest", f.guest())); workflow.submit(f.submit(Kind.INTERNAL, "notice-internal"));
    n.worker.poll(); assertEquals(2, n.notes.count()); assertEquals(4, count(Complaint.class));
    var rows = n.notes.findAll();
    var seller = rows.stream().filter(r -> r.getReferenceId().equals(invoice.complaintId())).findFirst().orElseThrow();
    assertEquals(CUSTOMER, seller.getRecipientCustomerId()); assertEquals(PORTAL, seller.getCustomerTenantId());
    assertEquals(SELLER, seller.getSourceTenantId()); assertEquals("SHOP-OPAQUE", seller.getDistributionPartnerId());
    assertEquals(NotificationContracts.Type.COMPLAINT, seller.getType());
    assertEquals(new NotificationContracts.ActionTarget(NotificationContracts.ActionKind.COMPLAINT_DETAIL, invoice.complaintId()), seller.getActionTarget());
    var platformRow = rows.stream().filter(r -> r.getReferenceId().equals(platform.complaintId())).findFirst().orElseThrow();
    assertEquals(PORTAL, platformRow.getSourceTenantId()); assertNull(platformRow.getDistributionPartnerId());
    assertEquals(2, n.service.unreadCount(NotificationContracts.Type.COMPLAINT).unreadCount());
    n.service.markRead(seller.getIdAsString(), true); assertEquals(1, n.service.unreadCount(null).unreadCount());
    when(n.identities.requirePurchaseSession()).thenReturn(new CustomerIdentityService.CustomerSession(OTHER, PORTAL));
    assertEquals(0, n.service.list(null, null, 0, 10).getTotalElements()); assertEquals(0, n.service.unreadCount(null).unreadCount());
    assertThrows(IllegalArgumentException.class, () -> n.service.markRead(seller.getIdAsString(), true));
    assertEquals(4, mongo.count(Query.query(Criteria.where("state").is(DispatchState.ACKNOWLEDGED)), ComplaintOutbox.class));
    assertEquals(originalInvoice, mongo.findById(new ObjectId(INVOICE), Document.class, "invoice"));
    assertEquals(originalLink, mongo.findById(originalLink.getObjectId("_id"), Document.class, "customer_invoice_link"));
  }

  @Test void notificationLifecycleDeduplicatesSpecificEventsAndNeverCopiesInternalNotes() throws Exception {
    var n = new NotificationHarness(); var submitted = workflow.submit(f.submit(Kind.INVOICE, "notice-lifecycle"));
    var assigned = workflow.assign(f.command(submitted, "assign", f.handler(), Visibility.INTERNAL), HANDLER);
    var start = workflow.startProcessing(f.command(assigned, "start", f.handler(), Visibility.INTERNAL));
    var wait = workflow.requestAdditionalInformation(f.command(start, "ask", f.handler(), Visibility.PUBLIC));
    var resume = workflow.resumeProcessing(f.command(wait, "resume", f.customer(), Visibility.PUBLIC));
    var resolve = workflow.resolve(f.command(resume, "resolve", f.handler(), Visibility.PUBLIC));
    var close = workflow.close(f.command(resolve, "close", f.customer(), Visibility.PUBLIC));
    n.worker.poll(); assertEquals(7, n.notes.count()); assertEquals(10, count(ComplaintOutbox.class));
    assertEquals(1, n.notes.findAll().stream().filter(v -> v.getTitle().contains("bổ sung")).count());
    assertEquals(1, n.notes.findAll().stream().filter(v -> v.getTitle().contains("giải quyết")).count());
    assertEquals(1, n.notes.findAll().stream().filter(v -> v.getTitle().contains("đóng")).count());
    for (var row : n.notes.findAll()) {
      assertFalse(row.getMessage().contains("Reason for workflow")); assertFalse(row.getMessage().contains(HANDLER));
      assertFalse(row.getMessage().contains("review")); assertEquals(CUSTOMER, row.getRecipientCustomerId());
    }
    n.worker.poll(); assertEquals(7, n.notes.count()); assertEquals(Status.CLOSED, get(close).getStatus());
  }

  @Test void notificationOutageAndLostAckRecoverWithoutLosingComplaintOrDuplicatingNotification() throws Exception {
    var n = new NotificationHarness(); var submitted = workflow.submit(f.submit(Kind.PLATFORM, "notice-retry"));
    doThrow(new DataAccessResourceFailureException("injected notification failure")).doCallRealMethod().when(n.service).create(any());
    n.worker.poll(); assertEquals(0, n.notes.count()); assertEquals(Status.SUBMITTED, get(submitted).getStatus());
    var row = mongo.findOne(new Query(), ComplaintOutbox.class); assertEquals(DispatchState.NEW, row.getState()); assertEquals("CONSUMER_RETRY", row.getLastErrorCode());
    f.clock.advance(31); doReturn(false).doCallRealMethod().when(outbox).acknowledge(any());
    n.worker.poll(); assertEquals(1, n.notes.count());
    f.clock.advance(121); n.worker.poll(); assertEquals(1, n.notes.count());
    assertEquals(DispatchState.ACKNOWLEDGED, mongo.findOne(new Query(), ComplaintOutbox.class).getState());
    assertEquals(1, count(ComplaintEvent.class)); assertEquals(1, count(Complaint.class));
  }

  @Test void notificationConcurrentDuplicateDeliveryUsesUniqueUC016Index() throws Exception {
    var n = new NotificationHarness(); var result = workflow.submit(f.submit(Kind.INVOICE, "notice-concurrent"));
    var event = mongo.findOne(new Query(), ComplaintOutbox.class).getEvent(); var notice = n.source.forEvent(event).orElseThrow();
    var pool = Executors.newFixedThreadPool(4);
    try {
      var gate = new CountDownLatch(1); var futures = new ArrayList<Future<NotificationContracts.CreationResult>>();
      for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { gate.await(); return n.service.create(notice.notification()); }));
      gate.countDown(); var ids = new HashSet<String>(); int created = 0;
      for (var future : futures) { var value = future.get(30, TimeUnit.SECONDS); ids.add(value.notificationId()); if (!value.duplicate()) created++; }
      assertEquals(1, ids.size()); assertEquals(1, created); assertEquals(1, n.notes.count());
      n.worker.poll(); assertEquals(1, n.notes.count()); assertEquals(0, get(result).getVersion());
    } finally { pool.shutdownNow(); }
  }

  @Test void notificationEscalationUpdatesAuthorizedInboxAndKeepsReporterSourceUnchanged() throws Exception {
    var h = new ComplaintIntakeHarness(this); var n = new NotificationHarness(); var management = processing(h);
    ComplaintIntakeResponses.Confirmation created;
    try (var auth = customerContext(CUSTOMER)) {
      created = h.intake.customerInvoice(new ComplaintIntakeRequests.CustomerInvoice(INVOICE, "notice-route", "SERVICE_ISSUE", "Service", "Description", null));
    }
    try (var auth = employeeContext(HANDLER, SELLER)) {
      management.assign(created.complaintId(), new ComplaintProcessingContracts.Assignment("notice-assign", 0L, "Private assignment", HANDLER));
      management.action(created.complaintId(), action("notice-start", 1), Operation.START_PROCESSING);
      management.action(created.complaintId(), action("notice-escalate", 2), Operation.ESCALATE);
      assertEquals(0, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
      assertThrows(IllegalArgumentException.class, () -> management.detail(created.complaintId()));
    }
    try (var auth = employeeContext(AUTHORITY, SELLER)) {
      assertEquals(1, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
      assertEquals("final", management.detail(created.complaintId()).complaint().queueId());
    }
    n.worker.poll(); assertEquals(4, n.notes.count());
    var moved = n.notes.findAll().stream().filter(v -> v.getTitle().contains("chuyển cấp")).findFirst().orElseThrow();
    assertEquals(SELLER, moved.getSourceTenantId()); assertEquals(CUSTOMER, moved.getRecipientCustomerId());
    assertFalse(moved.getMessage().contains("Reason for processing")); assertFalse(moved.getMessage().contains("final"));
  }

  @Test void allKindsPersistWithScopedQueriesNonUniqueInvoiceAndUnchangedOriginalRecords() {
    var invoiceA = workflow.submit(f.submit(Kind.INVOICE, "invoice-a"));
    var invoiceB = workflow.submit(f.submit(Kind.INVOICE, "invoice-b"));
    var platform = workflow.submit(f.submit(Kind.PLATFORM, "platform"));
    var internal = workflow.submit(f.submit(Kind.INTERNAL, "internal"));
    assertNotEquals(invoiceA.complaintCode(), invoiceB.complaintCode()); assertEquals(4, count(Complaint.class)); assertEquals(4, count(ComplaintEvent.class));
    assertEquals(0, invoiceA.version()); assertEquals(1, invoiceA.sequence()); assertNull(get(invoiceA).getPendingCommit());
    assertEquals(SELLER, get(invoiceA).getInvoiceTenantId()); assertEquals(PORTAL, get(invoiceA).getReporterHomeTenantId());
    assertEquals("SHOP-OPAQUE", get(invoiceA).getDistributionPartnerId()); assertEquals(Boundary.PLATFORM, get(platform).getProcessingBoundary());
    assertNull(get(platform).getInvoiceId()); assertNull(get(platform).getOriginTenantId()); assertEquals(SELLER, get(internal).getOriginTenantId());
    var own = new ReporterScope(ActorType.CUSTOMER, CUSTOMER, PORTAL);
    assertEquals(3, complaints.query(own, Filter.all(), PageRequest.of(0, 10)).getTotalElements());
    assertEquals(0, complaints.query(new ReporterScope(ActorType.CUSTOMER, OTHER, PORTAL), Filter.all(), PageRequest.of(0, 10)).getTotalElements());
    var tenant = new QueueScope(new ProcessingScope(Boundary.TENANT, SELLER), Set.of("review"), true, Set.of());
    assertEquals(3, complaints.query(tenant, Filter.all(), PageRequest.of(0, 2)).getTotalElements());
    assertEquals(2, complaints.query(tenant, new Filter(Set.of(Status.SUBMITTED), Set.of(Kind.INVOICE), f.clock.instant(), f.clock.instant()), PageRequest.of(0, 10, Sort.by("complaintCode"))).getTotalElements());
    assertEquals(0, complaints.query(new QueueScope(new ProcessingScope(Boundary.TENANT, PORTAL), Set.of("review"), true, Set.of()), Filter.all(), PageRequest.of(0, 10)).getTotalElements());
    assertEquals(1, complaints.query(new QueueScope(tenant.processing(), tenant.queueIds(), false, Set.of(invoiceA.complaintId())), Filter.all(), PageRequest.of(0, 10)).getTotalElements());
    assertTrue(complaints.findByCode(invoiceA.complaintCode(), own).isPresent()); assertTrue(complaints.findById(platform.complaintId(), tenant).isEmpty());
    var copy = get(invoiceB); copy.setId(new ObjectId()); copy.setSubmissionKey("different-key"); copy.setComplaintCode(invoiceA.complaintCode());
    assertThrows(DuplicateKeyException.class, () -> complaints.insert(copy));
    assertEquals(originalInvoice, mongo.findById(new ObjectId(INVOICE), Document.class, "invoice"));
    assertEquals(originalLink, mongo.findById(originalLink.getObjectId("_id"), Document.class, "customer_invoice_link"));
  }
  @Test void invoiceAuthorityRouteWorksWithoutCatalogAndSeparatesIssuerFromProcessor() {
    mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(INVOICE))), new Update().set("submittedBy", EMPLOYEE), Invoice.class);
    var before = mongo.findById(new ObjectId(INVOICE), Document.class, "invoice");
    var h = new ComplaintIntakeHarness(this, true); var management = processing(h);
    assertEquals(0, count(ComplaintRoutingCatalog.class));
    assertTrue(h.intake.guestOptions(h.token).categories().stream().anyMatch(c -> c.code().equals("SERVICE_ISSUE")));
    var created = guestCase(h, "native-guest"); var row = complaints.findCommitted(created.complaintId()).orElseThrow();
    assertEquals(SELLER, row.getProcessingTenantId()); assertEquals(EMPLOYEE, row.getInvoiceSubmittedBy()); assertNull(row.getAssigneeUserId());
    assertEquals(InvoiceAuthorityRoute.POLICY, row.getRoutingPolicyId()); assertEquals(InvoiceAuthorityRoute.queue(SELLER), row.getCurrentQueueId());
    for (String regular : List.of(EMPLOYEE, HANDLER)) try (var auth = employeeContext(regular, SELLER)) {
      assertEquals(0, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
      assertThrows(IllegalArgumentException.class, () -> management.detail(created.complaintId()));
      assertThrows(ForbiddenException.class, () -> management.action(created.complaintId(), action("not-admin", 0), Operation.START_PROCESSING));
    }
    try (var auth = employeeContext(AUTHORITY, SELLER)) {
      assertEquals(1, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
      var detail = management.detail(created.complaintId()); assertEquals(EMPLOYEE, detail.invoiceData().submittedBy());
      assertEquals(List.of(AUTHORITY), detail.capabilities().assignees().stream().map(ComplaintProcessingContracts.Candidate::userId).toList());
      assertThrows(ForbiddenException.class, () -> management.assign(created.complaintId(), new ComplaintProcessingContracts.Assignment("assign-issuer", 0L, "Wrong authority", EMPLOYEE)));
      var assigned = management.assign(created.complaintId(), new ComplaintProcessingContracts.Assignment("assign-admin", 0L, "Admin receives", AUTHORITY));
      var start = management.action(created.complaintId(), action("start-admin", assigned.version()), Operation.START_PROCESSING);
      management.action(created.complaintId(), action("resolve-admin", start.version()), Operation.RESOLVE);
    }
    assertEquals(before, mongo.findById(new ObjectId(INVOICE), Document.class, "invoice"));
    assertEquals(originalLink, mongo.findById(originalLink.getObjectId("_id"), Document.class, "customer_invoice_link"));
  }
  @Test void invoiceAuthorityCustomerOwnershipAndCurrentAuthorityAreRecheckedWithoutImplicitCrossTenantAccess() {
    var h = new ComplaintIntakeHarness(this, true); var management = processing(h); ComplaintIntakeResponses.Confirmation created;
    try (var auth = customerContext(CUSTOMER)) {
      assertTrue(h.intake.customerOptions(Kind.INVOICE, INVOICE).categories().stream().anyMatch(c -> c.code().equals("SERVICE_ISSUE")));
      var input = new ComplaintIntakeRequests.CustomerInvoice(INVOICE, "native-customer", "SERVICE_ISSUE", "Service", "Please review", null);
      created = h.intake.customerInvoice(input); assertEquals(created, h.intake.customerInvoice(input));
      assertEquals(1, h.intake.customerMine(0, 10, null, null).getTotalElements());
    }
    try (var auth = customerContext(OTHER)) {
      var other = new UserEntity(); other.setId(new ObjectId(OTHER)); when(h.customers.requireSelfServiceAccount(anyString())).thenReturn(other);
      assertThrows(IllegalArgumentException.class, () -> h.intake.customerOptions(Kind.INVOICE, INVOICE));
      assertThrows(IllegalArgumentException.class, () -> h.intake.customerDetail(created.complaintId()));
    }
    try (var auth = employeeContext(PLATFORM_HANDLER, PLATFORM)) {
      assertEquals(0, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
      assertThrows(IllegalArgumentException.class, () -> management.detail(created.complaintId()));
    }
    h.finalAuthorityUsers.add(HANDLER);
    for (String admin : List.of(AUTHORITY, HANDLER)) try (var auth = employeeContext(admin, SELLER)) {
      assertEquals(1, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
    }
    h.finalAuthorityUsers.remove(AUTHORITY);
    try (var auth = employeeContext(AUTHORITY, SELLER)) {
      assertEquals(0, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
      assertThrows(ForbiddenException.class, () -> management.action(created.complaintId(), action("revoked-admin", 0), Operation.START_PROCESSING));
    }
    assertEquals(1, count(Complaint.class)); assertEquals(1, count(ComplaintEvent.class)); assertEquals(1, count(ComplaintOutbox.class));
  }
  @Test void invoiceAuthoritySubmissionRemainsPendingInSellerInboxWhenNoAuthorizedHandlerIsAvailable() {
    var h = new ComplaintIntakeHarness(this, true); h.finalAuthorityUsers.clear();
    assertFalse(h.intake.guestOptions(h.token).categories().isEmpty());
    var created = guestCase(h, "native-pending"); var row = complaints.findCommitted(created.complaintId()).orElseThrow();
    assertEquals(Status.SUBMITTED, row.getStatus()); assertEquals(SELLER, row.getProcessingTenantId()); assertNull(row.getAssigneeUserId());
    assertTrue(h.directory.memberships(row.getCurrentQueueId()).isEmpty());
    try (var auth = employeeContext(HANDLER, SELLER)) { assertThrows(IllegalArgumentException.class, () -> processing(h).detail(created.complaintId())); }
    assertEquals(0, count(ComplaintRoutingCatalog.class));
  }
  @Test void invoiceAuthorityRuleCannotBeBypassedByALegacyQueueGrantToAnOrdinaryEmployee() {
    var previous = new ComplaintIntakeHarness(this); var old = guestCase(previous, "old-case");
    var current = new ComplaintIntakeHarness(this, true); var management = processing(current);
    try (var auth = employeeContext(HANDLER, SELLER)) {
      assertEquals(0, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
      assertThrows(IllegalArgumentException.class, () -> management.detail(old.complaintId()));
      assertThrows(ForbiddenException.class, () -> management.action(old.complaintId(), action("old-grant-bypass", 0), Operation.START_PROCESSING));
    }
    assertEquals("review", complaints.findCommitted(old.complaintId()).orElseThrow().getCurrentQueueId());
    assertEquals(0, complaints.findCommitted(old.complaintId()).orElseThrow().getVersion());
  }
  @Test void workflowWritesConsecutiveImmutableHistoryAndSeparatesReporterVisibility() {
    var submitted = workflow.submit(f.submit(Kind.INVOICE, "submit"));
    var started = workflow.startProcessing(f.command(submitted, "start", f.handler(), Visibility.INTERNAL));
    var waiting = workflow.requestAdditionalInformation(f.command(started, "request-info", f.handler(), Visibility.PUBLIC));
    var resumed = workflow.resumeProcessing(f.command(waiting, "reply", f.customer(), Visibility.PUBLIC));
    var resolved = workflow.resolve(f.command(resumed, "resolve", f.handler(), Visibility.PUBLIC));
    var closed = workflow.close(f.command(resolved, "close", f.customer(), Visibility.PUBLIC));
    assertEquals(Status.CLOSED, get(closed).getStatus()); assertNotNull(get(closed).getResolvedAt()); assertNotNull(get(closed).getClosedAt());
    var all = history.processing(closed.complaintId(), f.handler(), 0, 100).getContent();
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), all.stream().map(ComplaintEvent::sequence).toList());
    assertEquals(5, history.reporter(closed.complaintId(), f.customer(), null, 0, 100).getTotalElements());
    assertEquals(9, count(ComplaintOutbox.class)); assertEquals(5, closed.version());
    var original = all.get(0);
    var forged = new ComplaintEvent(original.eventId(), original.complaintId(), original.sequence(), original.eventType(), original.actorType(), original.actorId(),
        original.fromStatus(), original.toStatus(), original.fromQueueId(), original.toQueueId(), "Tampered history", original.visibility(), original.occurredAt(),
        original.correlationId(), original.commandKey(), original.payloadFingerprint(), original.result());
    assertThrows(IllegalStateException.class, () -> events.append(forged));
    assertEquals(original, events.findCommand(original.commandKey()).orElseThrow());
    assertThrows(ForbiddenException.class, () -> history.processing(closed.complaintId(), f.customer(), 0, 10));
    assertThrows(BadRequestException.class, () -> workflow.startProcessing(f.command(closed, "reopen", f.handler(), Visibility.PUBLIC)));
    assertEquals(started, workflow.startProcessing(f.command(submitted, "start", f.handler(), Visibility.INTERNAL))); // Original outcome, not current CLOSED snapshot.
  }
  @Test void concurrentSameSubmissionKeyReturnsOneCanonicalComplaintAndConflictingPayloadIsRejected() throws Exception {
    var command = f.submit(Kind.PLATFORM, "concurrent"); var pool = Executors.newFixedThreadPool(6);
    try {
      var gate = new CountDownLatch(1); var futures = new ArrayList<Future<CommandResult>>();
      for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> { gate.await(); return workflow.submit(command); })); gate.countDown();
      var result = futures.get(0).get(30, TimeUnit.SECONDS);
      for (var future : futures) assertEquals(result, future.get(30, TimeUnit.SECONDS));
      projection.recover(); assertEquals(1, count(Complaint.class)); assertEquals(1, count(ComplaintEvent.class)); assertEquals(1, count(ComplaintOutbox.class));
      assertThrows(IllegalStateException.class, () -> workflow.submit(f.content(command, "Different", command.description())));
    } finally { pool.shutdownNow(); }
  }
  @Test void concurrentStateChangesUseVersionCasAndDoNotLoseUpdates() throws Exception {
    var submitted = workflow.submit(f.submit(Kind.INVOICE, "submit")); var pool = Executors.newFixedThreadPool(2);
    try {
      var gate = new CountDownLatch(1); var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++) { String request = "start-" + i; futures.add(pool.submit(() -> { gate.await(); try {
        workflow.startProcessing(f.command(submitted, request, f.handler(), Visibility.PUBLIC)); return true;
      } catch (OptimisticLockingFailureException ex) { return false; } })); }
      gate.countDown(); int successes = 0; for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes); projection.recover(); assertEquals(1, get(submitted).getVersion());
      assertEquals(2, count(ComplaintEvent.class)); assertEquals(2, count(ComplaintOutbox.class));
    } finally { pool.shutdownNow(); }
  }
  @Test void concurrentSameWorkflowRequestIsIdempotentEvenAfterOriginalVersionBecameStale() throws Exception {
    var submitted = workflow.submit(f.submit(Kind.INVOICE, "submit")); var command = f.command(submitted, "start-once", f.handler(), Visibility.PUBLIC);
    var pool = Executors.newFixedThreadPool(4);
    try {
      var gate = new CountDownLatch(1); var futures = new ArrayList<Future<CommandResult>>();
      for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { gate.await(); return workflow.startProcessing(command); })); gate.countDown();
      var result = futures.get(0).get(30, TimeUnit.SECONDS); for (var future : futures) assertEquals(result, future.get(30, TimeUnit.SECONDS));
      assertEquals(1, result.version()); assertEquals(2, count(ComplaintEvent.class));
      var changed = new WorkflowCommand(command.complaintId(), command.expectedVersion(), command.requestId(), command.correlationId(), command.actor(), null, "Different reason", command.visibility());
      assertThrows(IllegalStateException.class, () -> workflow.startProcessing(changed));
    } finally { pool.shutdownNow(); }
  }
  @Test void historyFailureRetainsDurableIntentBlocksNewWritesAndRecoversAfterRestart() {
    doThrow(new DataAccessResourceFailureException("injected history failure")).doCallRealMethod().when(events).append(any());
    var result = workflow.submit(f.submit(Kind.INVOICE, "submit"));
    assertNotNull(get(result).getPendingCommit()); assertEquals(0, count(ComplaintEvent.class)); assertEquals(0, count(ComplaintOutbox.class));
    assertEquals(1, history.reporter(result.complaintId(), f.customer(), null, 0, 10).getTotalElements());
    assertThrows(IllegalStateException.class, () -> workflow.startProcessing(f.command(result, "start", f.handler(), Visibility.PUBLIC)));
    f.clock.advance(10); services(); projection.recover(); assertNull(get(result).getPendingCommit()); assertEquals(0, get(result).getVersion());
    assertEquals(1, count(ComplaintEvent.class)); assertEquals(1, count(ComplaintOutbox.class));
    assertEquals(result, workflow.submit(f.submit(Kind.INVOICE, "submit")));
    assertEquals(1, workflow.startProcessing(f.command(result, "start", f.handler(), Visibility.PUBLIC)).version());
  }
  @Test void failureBetweenHistoryAndOutboxAndBeforeAckReplaysWithoutOverwritingAcknowledgedDelivery() {
    doThrow(new DataAccessResourceFailureException("injected outbox failure")).doCallRealMethod().when(outbox).append(any());
    var result = workflow.submit(f.submit(Kind.PLATFORM, "submit")); assertEquals(1, count(ComplaintEvent.class)); assertEquals(0, count(ComplaintOutbox.class));
    f.clock.advance(10);
    doThrow(new DataAccessResourceFailureException("injected ack failure")).doCallRealMethod().when(complaints).acknowledgeProjection(any(), anyString());
    projection.recover(); assertEquals(1, count(ComplaintOutbox.class)); assertNotNull(get(result).getPendingCommit());
    var lease = outbox.claimNext("test-consumer").orElseThrow(); assertTrue(outbox.acknowledge(lease));
    f.clock.advance(10); services(); projection.recover(); assertNull(get(result).getPendingCommit());
    assertEquals(0, get(result).getVersion()); assertEquals(1, count(ComplaintEvent.class)); assertEquals(1, count(ComplaintOutbox.class));
    assertTrue(outbox.claimNext("test-consumer").isEmpty());
  }
  @Test void failedRootWriteCreatesNoHistoryOrOutboxAndUnknownAcknowledgementCanRecover() {
    doThrow(new DataAccessResourceFailureException("before commit")).when(complaints).insert(any());
    var command = f.submit(Kind.PLATFORM, "submit");
    assertThrows(DataAccessResourceFailureException.class, () -> workflow.submit(command)); projection.recover();
    assertEquals(0, count(Complaint.class)); assertEquals(0, count(ComplaintEvent.class)); assertEquals(0, count(ComplaintOutbox.class));
    doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("unknown acknowledgement after actual commit"); }).when(complaints).insert(any());
    assertThrows(DataAccessResourceFailureException.class, () -> workflow.submit(command));
    assertEquals(1, count(Complaint.class)); assertEquals(0, count(ComplaintEvent.class));
    services(); projection.recover(); var recovered = workflow.submit(command);
    assertEquals(1, count(Complaint.class)); assertEquals(1, count(ComplaintEvent.class)); assertEquals(recovered, workflow.submit(command));
  }
  @Test void guestHistoryIsCaseBoundAndNeverIncludesInternalNotesOrEmployeeMetadata() {
    var submitted = workflow.submit(f.submit(Kind.INVOICE, "guest-submit", f.guest()));
    workflow.startProcessing(f.command(submitted, "start", f.handler(), Visibility.INTERNAL));
    var proof = new VerifiedGuestAccess(submitted.complaintId(), f.clock.instant().plusSeconds(60));
    var rows = history.reporter(submitted.complaintId(), f.guest(), proof, 0, 10);
    assertEquals(1, rows.getTotalElements()); assertEquals("Complaint submitted", rows.getContent().get(0).reason());
    assertThrows(ForbiddenException.class, () -> history.reporter(submitted.complaintId(), f.customer(), null, 0, 10));
    assertThrows(ForbiddenException.class, () -> history.reporter(submitted.complaintId(), f.guest(), new VerifiedGuestAccess(CASE, proof.expiresAt()), 0, 10));
    var internal = workflow.submit(f.submit(Kind.INTERNAL, "internal"));
    assertThrows(ForbiddenException.class, () -> history.processing(internal.complaintId(), f.employee(), 0, 10));
    assertEquals(1, history.reporter(internal.complaintId(), f.employee(), null, 0, 10).getTotalElements());
  }
  @Test void outboxConsumerRetryAndExpiredLeasesPreserveStableEventIdentity() {
    workflow.submit(f.submit(Kind.PLATFORM, "submit")); var lease = outbox.claimNext("consumer-a").orElseThrow();
    assertTrue(outbox.claimNext("consumer-b").isEmpty()); assertTrue(outbox.retry(lease)); assertFalse(outbox.acknowledge(lease));
    f.clock.advance(31); var next = outbox.claimNext("consumer-b").orElseThrow(); assertEquals(lease.event(), next.event());
    f.clock.advance(121); assertFalse(outbox.acknowledge(next)); var resumed = outbox.claimNext("consumer-c").orElseThrow();
    assertTrue(outbox.acknowledge(resumed)); assertTrue(outbox.claimNext("consumer-a").isEmpty());
    var bad = new DomainEvent(resumed.event().eventId(), resumed.event().complaintId(), 99, resumed.event().eventType(), resumed.event().occurredAt(), "different");
    assertThrows(IllegalStateException.class, () -> outbox.append(bad));
  }
  @Test void intakeGuestReceiptBindsSellerAndItemsAndIssuesSeparateHashedStableProof() {
    var h = new ComplaintIntakeHarness(this);
    var input = new ComplaintIntakeRequests.InvoiceIssue("guest-intake", "SERVICE_ISSUE", "Service problem", "Please investigate", null);
    var answer = h.intake.guest(h.token, input); var repeated = h.intake.guest(h.token, input);
    assertEquals(answer, repeated); assertEquals(1, count(Complaint.class)); assertEquals(1, count(ComplaintEvent.class)); assertEquals(1, count(ComplaintOutbox.class));
    var row = complaints.findCommitted(answer.complaint().complaintId()).orElseThrow();
    assertEquals(SELLER, row.getInvoiceTenantId()); assertEquals("SHOP-OPAQUE", row.getDistributionPartnerId()); assertEquals(ActorType.GUEST, row.getReporterType());
    assertFalse(row.isIdentityVerified()); assertNull(row.getReporterUserId()); assertTrue(row.getInvoiceItemReferences().isEmpty());
    var secret = answer.trackingUrl().split("#proof=")[1].split("\\.")[1];
    var access = mongo.findById(row.getIdAsString(), GuestComplaintAccess.class); assertNotEquals(secret, access.tokenHash());
    var tracked = h.intake.track(new ComplaintIntakeRequests.Track(row.getIdAsString(), secret)); assertEquals(row.getComplaintCode(), tracked.complaint().complaintCode());
    assertEquals(1, tracked.history().size()); assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> h.proof.verify(row.getIdAsString(), "z".repeat(43)));
    h.proof.revoke(row.getIdAsString()); assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> h.proof.verify(row.getIdAsString(), secret));
    assertEquals(originalInvoice, mongo.findById(new ObjectId(INVOICE), Document.class, "invoice"));
    assertEquals(originalLink, mongo.findById(originalLink.getObjectId("_id"), Document.class, "customer_invoice_link"));
  }
  @Test void intakeRejectsInvalidReceiptAndForeignItemButAllowsSeveralDistinctComplaints() {
    var h = new ComplaintIntakeHarness(this);
    when(h.receipts.requireValidReceipt("expired")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE));
    var input = new ComplaintIntakeRequests.InvoiceIssue("bad-item", "PRODUCT_ISSUE", "Product problem", "Please investigate", "line:9");
    assertThrows(BadRequestException.class, () -> h.intake.guest(h.token, input));
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> h.intake.guest("expired", input)); assertEquals(0, count(Complaint.class));
    for (String request : List.of("first", "second")) h.intake.guest(h.token, new ComplaintIntakeRequests.InvoiceIssue(request, "PRODUCT_ISSUE", "Product problem", "Please investigate", "line:0"));
    assertEquals(2, count(Complaint.class)); assertEquals(2, count(GuestComplaintAccess.class));
  }
  @Test void intakeCustomerClaimOwnershipAndPlatformRoutingStaySeparateFromPortalTenant() {
    var h = new ComplaintIntakeHarness(this);
    try (var auth = customerContext(CUSTOMER)) {
      var own = h.intake.customerInvoice(new ComplaintIntakeRequests.CustomerInvoice(INVOICE, "customer-invoice", "PRODUCT_ISSUE", "Product issue", "Please investigate", "line:0"));
      var system = h.intake.platform(new ComplaintIntakeRequests.PlatformIssue("customer-platform", "SYSTEM_ERROR", "System issue", "History is not loading", new ComplaintIntakeRequests.TechnicalContext("/customer/invoices", "history", "TIMEOUT")));
      var invoiceRow = complaints.findCommitted(own.complaintId()).orElseThrow(); var systemRow = complaints.findCommitted(system.complaintId()).orElseThrow();
      assertEquals(PORTAL, invoiceRow.getReporterHomeTenantId()); assertEquals(SELLER, invoiceRow.getInvoiceTenantId());
      assertEquals(PLATFORM, systemRow.getProcessingTenantId()); assertEquals("platform-support", systemRow.getCurrentQueueId()); assertNull(systemRow.getInvoiceId()); assertNull(systemRow.getDistributionPartnerId());
      assertEquals(2, h.intake.customerMine(0, 1, null, null).getTotalElements()); assertEquals(own.complaintCode(), h.intake.customerDetail(own.complaintId()).complaint().complaintCode());
      assertThrows(IllegalArgumentException.class, () -> h.intake.customerInvoice(new ComplaintIntakeRequests.CustomerInvoice(CASE, "foreign", "PRODUCT_ISSUE", "Issue", "Description", null)));
      var different = new com.ecofood.app.userACL.entity.UserEntity(); different.setId(new ObjectId(OTHER)); when(h.customers.requireSelfServiceAccount(anyString())).thenReturn(different);
      assertEquals(0, h.intake.customerMine(0, 10, null, null).getTotalElements()); assertThrows(IllegalArgumentException.class, () -> h.intake.customerDetail(own.complaintId()));
    }
  }
  @Test void intakeInternalUsesAuthenticatedOriginAndPolicyNextQueueAndDeniesForeignReporter() {
    var h = new ComplaintIntakeHarness(this);
    try (var auth = org.mockito.Mockito.mockStatic(com.ecofood.core.security.AuthenticationContext.class)) {
      auth.when(com.ecofood.core.security.AuthenticationContext::getClaims).thenReturn(io.jsonwebtoken.Jwts.claims().subject(EMPLOYEE).add("tenantId", SELLER).build());
      auth.when(com.ecofood.core.security.AuthenticationContext::getPermissions).thenReturn(Arrays.stream(Permission.values()).map(Enum::name).toList());
      var answer = h.intake.internal(new ComplaintIntakeRequests.InternalIssue("internal-intake", "TEAM", "Internal issue", "Please investigate"));
      var row = complaints.findCommitted(answer.complaintId()).orElseThrow(); assertEquals(SELLER, row.getOriginTenantId()); assertEquals("review", row.getCurrentQueueId()); assertEquals("origin", row.getReporterOriginQueueId());
      assertEquals(1, h.intake.employeeMine(0, 10, null).getTotalElements()); assertEquals(answer.complaintCode(), h.intake.employeeDetail(answer.complaintId()).complaint().complaintCode());
      auth.when(com.ecofood.core.security.AuthenticationContext::getClaims).thenReturn(io.jsonwebtoken.Jwts.claims().subject(HANDLER).add("tenantId", SELLER).build());
      assertEquals(0, h.intake.employeeMine(0, 10, null).getTotalElements()); assertThrows(IllegalArgumentException.class, () -> h.intake.employeeDetail(answer.complaintId()));
      assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> h.intake.internal(new ComplaintIntakeRequests.InternalIssue("unmapped", "TEAM", "Issue", "Description")));
    }
  }
  @Test void intakeConcurrentRetriesUseOneComplaintAndProofAndConflictOnChangedPayload() throws Exception {
    var h = new ComplaintIntakeHarness(this); var pool = Executors.newFixedThreadPool(4);
    var input = new ComplaintIntakeRequests.InvoiceIssue("concurrent-intake", "SERVICE_ISSUE", "Service issue", "Please investigate", null);
    try {
      var gate = new CountDownLatch(1); var futures = new ArrayList<Future<ComplaintIntakeResponses.GuestConfirmation>>();
      for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { gate.await(); return h.intake.guest(h.token, input); })); gate.countDown();
      var first = futures.get(0).get(30, TimeUnit.SECONDS); for (var next : futures) assertEquals(first, next.get(30, TimeUnit.SECONDS));
      projection.recover(); assertEquals(1, count(Complaint.class)); assertEquals(1, count(GuestComplaintAccess.class)); assertEquals(1, count(ComplaintEvent.class)); assertEquals(1, count(ComplaintOutbox.class));
      assertThrows(IllegalStateException.class, () -> h.intake.guest(h.token, new ComplaintIntakeRequests.InvoiceIssue(input.requestId(), input.categoryCode(), "Different", input.description(), null)));
    } finally { pool.shutdownNow(); }
  }
  @Test void intakeRateLimitUsesSharedMongoCountersAndProofExpiresIndependently() {
    var h = new ComplaintIntakeHarness(this);
    for (int i = 0; i < 10; i++) h.rates.ip("test-ip", false);
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> h.rates.ip("test-ip", false));
    var answer = h.intake.guest(h.token, new ComplaintIntakeRequests.InvoiceIssue("expires", "SERVICE_ISSUE", "Service issue", "Please investigate", null));
    String secret = answer.trackingUrl().split("#proof=")[1].split("\\.")[1]; f.clock.advance(7 * 86400);
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> h.proof.verify(answer.complaint().complaintId(), secret)); h.rates.ip("test-ip", false);
    var counters = mongo.find(new Query(), Document.class, "complaint_intake_rate"); assertTrue(counters.stream().noneMatch(d -> d.toJson().contains("test-ip")));
  }
  @Test void intakeCatalogFailSafeAndRetryRemainsStableAfterConfigurationVersionChanges() {
    var h = new ComplaintIntakeHarness(this); var input = new ComplaintIntakeRequests.InvoiceIssue("catalog-change", "SERVICE_ISSUE", "Service issue", "Please investigate", null);
    var before = h.intake.guest(h.token, input);
    var changed = new ComplaintRoutingCatalog.Configuration(2, h.config.approvedBy(), h.config.categories(), h.config.queues(), h.config.memberships(), h.config.policies(), h.config.grants());
    h.importer.install(changed); h.importer.install(changed); assertEquals(before, h.intake.guest(h.token, input));
    var denied = new ComplaintRoutingCatalog.Configuration(3, changed.approvedBy(), changed.categories(), changed.queues(), changed.memberships(), changed.policies(), List.of());
    h.importer.install(denied); assertThrows(ForbiddenException.class, () -> h.intake.guest(h.token, new ComplaintIntakeRequests.InvoiceIssue("no-scope", "SERVICE_ISSUE", "Issue", "Description", null)));
    assertEquals(1, count(Complaint.class));
  }
  @Test void intakeRejectsCredentialsAndUnsafeTechnicalContextWithoutPersistence() {
    var h = new ComplaintIntakeHarness(this);
    assertThrows(BadRequestException.class, () -> h.intake.guest(h.token, new ComplaintIntakeRequests.InvoiceIssue("secret", "SERVICE_ISSUE", "Issue", "password=do-not-store", null)));
    try (var auth = customerContext(CUSTOMER)) {
      assertThrows(BadRequestException.class, () -> h.intake.platform(new ComplaintIntakeRequests.PlatformIssue("technical", "SYSTEM_ERROR", "Issue", "Description", new ComplaintIntakeRequests.TechnicalContext("/customer/invoices?token=private", null, null))));
    }
    assertEquals(0, count(Complaint.class));
  }
  private org.mockito.MockedStatic<com.ecofood.core.security.AuthenticationContext> customerContext(String user) {
    var auth = org.mockito.Mockito.mockStatic(com.ecofood.core.security.AuthenticationContext.class);
    auth.when(com.ecofood.core.security.AuthenticationContext::getClaims).thenReturn(io.jsonwebtoken.Jwts.claims().subject(user).add("tenantId", PORTAL).add("identityType", "CUSTOMER").build());
    auth.when(com.ecofood.core.security.AuthenticationContext::getPermissions).thenReturn(List.of("COMPLAINT_CREATE", "COMPLAINT_READ")); return auth;
  }
  @Test void intakeRecoversWhenAnotherReplicaWonSameRequestWithEarlierRoutingSnapshot() {
    var h = new ComplaintIntakeHarness(this);
    var actualCore = (ComplaintWorkflowService) org.springframework.test.util.ReflectionTestUtils.getField(h.intake, "core");
    var concurrentCore = spy(actualCore);
    doAnswer(call -> {
      SubmitCommand c = call.getArgument(0); var route = c.routing();
      var older = new RoutingDecision("earlier-published-policy", route.policyVersion(), route.queueId(), route.processing(), route.reporterOriginQueueId());
      actualCore.submit(new SubmitCommand(c.requestId(), c.correlationId(), c.kind(), c.categoryCode(), c.subject(), c.description(), c.actor(), c.invoice(), c.invoiceItemReferences(), older, c.guestSourceKey()));
      throw new IllegalStateException("Concurrent winner used an earlier routing snapshot");
    }).doCallRealMethod().when(concurrentCore).submit(any());
    org.springframework.test.util.ReflectionTestUtils.setField(h.intake, "core", concurrentCore);
    var input = new ComplaintIntakeRequests.InvoiceIssue("replica-race", "SERVICE_ISSUE", "Service issue", "Please investigate", null);
    var answer = h.intake.guest(h.token, input); assertEquals(answer, h.intake.guest(h.token, input));
    assertEquals(1, count(Complaint.class)); assertEquals(1, count(ComplaintEvent.class)); assertEquals(1, count(ComplaintOutbox.class));
    assertEquals("earlier-published-policy", complaints.findCommitted(answer.complaint().complaintId()).orElseThrow().getRoutingPolicyId());
    assertThrows(IllegalStateException.class, () -> h.intake.guest(h.token, new ComplaintIntakeRequests.InvoiceIssue(input.requestId(), input.categoryCode(), "Different", input.description(), null)));
  }
  private ComplaintProcessingService processing(ComplaintIntakeHarness h) {
    var lookupStore = spy(store); doReturn(namespaces.get("invoice")).when(lookupStore).collectionName(Invoice.class);
    return new ComplaintProcessingService(h.directory, complaints, h.workflow, h.history, h.access, h.routing, new ComplaintInboxRepository(lookupStore, complaints), f.invoices);
  }
  private ComplaintProcessingContracts.Action action(String request, long version) { return new ComplaintProcessingContracts.Action(request, version, "Reason for processing"); }
  private org.mockito.MockedStatic<com.ecofood.core.security.AuthenticationContext> employeeContext(String user, String tenant) {
    var auth = org.mockito.Mockito.mockStatic(com.ecofood.core.security.AuthenticationContext.class);
    auth.when(com.ecofood.core.security.AuthenticationContext::getClaims).thenReturn(io.jsonwebtoken.Jwts.claims().subject(user).add("tenantId", tenant).build());
    auth.when(com.ecofood.core.security.AuthenticationContext::getPermissions).thenReturn(Arrays.stream(Permission.values()).map(Enum::name).toList()); return auth;
  }
  private ComplaintIntakeResponses.Confirmation guestCase(ComplaintIntakeHarness h, String request) {
    return h.intake.guest(h.token, new ComplaintIntakeRequests.InvoiceIssue(request, "SERVICE_ISSUE", "Service issue", "Please investigate", null)).complaint();
  }
  @Test void processingAssignmentAndStatusLifecycleRequireCurrentAssigneeAndKeepPublicHistorySafe() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var created = guestCase(h, "processing-assignment");
    var members = new ArrayList<>(h.config.memberships()); members.add(new ComplaintQueueMembership("second-handler", "review", EMPLOYEE, true, EnumSet.allOf(QueueCapability.class), Authority.NONE));
    var grants = new ArrayList<>(h.config.grants()); grants.add(new ComplaintRoutingCatalog.ScopeGrant(EMPLOYEE, "review", Set.of(Kind.INVOICE), Set.of(SELLER), false, true, Set.of()));
    h.importer.install(new ComplaintRoutingCatalog.Configuration(2, h.config.approvedBy(), h.config.categories(), h.config.queues(), members, h.config.policies(), grants));
    var assignment = new ComplaintProcessingContracts.Assignment("assign-one", 0L, "Assign specialist", EMPLOYEE);
    try (var auth = employeeContext(HANDLER, SELLER)) {
      assertEquals(1, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
      var assigned = management.assign(created.complaintId(), assignment); assertEquals(assigned, management.assign(created.complaintId(), assignment));
      assertEquals(EMPLOYEE, management.detail(created.complaintId()).complaint().assigneeUserId());
      assertThrows(ForbiddenException.class, () -> management.action(created.complaintId(), action("wrong-handler-start", 1), Operation.START_PROCESSING));
      assertThrows(IllegalStateException.class, () -> management.assign(created.complaintId(), new ComplaintProcessingContracts.Assignment("assign-one", 0L, "Different", EMPLOYEE)));
    }
    try (var auth = employeeContext(EMPLOYEE, SELLER)) {
      var start = management.action(created.complaintId(), action("assigned-start", 1), Operation.START_PROCESSING);
      var wait = management.action(created.complaintId(), action("info", start.version()), Operation.REQUEST_INFORMATION);
      var resumed = management.action(created.complaintId(), action("resume", wait.version()), Operation.RESUME_PROCESSING);
      var resolved = management.action(created.complaintId(), action("resolve", resumed.version()), Operation.RESOLVE);
      assertEquals(Status.RESOLVED, resolved.status()); assertNotNull(management.detail(created.complaintId()).resolvedAt());
      var timeline = management.history(created.complaintId(), 0, 100); assertEquals(6, timeline.getTotalElements());
      assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), timeline.getContent().stream().map(ComplaintProcessingContracts.HistoryEntry::sequence).toList());
      assertEquals(EMPLOYEE, timeline.getContent().get(1).processingChange().toAssigneeUserId());
      assertThrows(ForbiddenException.class, () -> management.assign(created.complaintId(), new ComplaintProcessingContracts.Assignment("resolved-assign", resolved.version(), "Too late", HANDLER)));
      assertThrows(BadRequestException.class, () -> management.action(created.complaintId(), action("resolved-start", resolved.version()), Operation.START_PROCESSING));
    }
    var proof = new VerifiedGuestAccess(created.complaintId(), f.clock.instant().plusSeconds(60));
    assertEquals(3, h.history.reporter(created.complaintId(), f.guest(), proof, 0, 100).getTotalElements()); assertEquals(8, count(ComplaintOutbox.class));
    assertEquals(originalInvoice, mongo.findById(new ObjectId(INVOICE), Document.class, "invoice"));
    assertEquals(originalLink, mongo.findById(originalLink.getObjectId("_id"), Document.class, "customer_invoice_link"));
  }
  @Test void processingEscalationPreservesStatusSourceAndSupportsOriginalReceiptAfterHandoff() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var created = guestCase(h, "escalate-basic");
    try (var auth = employeeContext(HANDLER, SELLER)) {
      assertThrows(BadRequestException.class, () -> management.action(created.complaintId(), action("early", 0), Operation.ESCALATE));
      var started = management.action(created.complaintId(), action("start", 0), Operation.START_PROCESSING);
      var escalation = action("escalate", started.version()); var moved = management.action(created.complaintId(), escalation, Operation.ESCALATE);
      assertEquals(Status.IN_PROGRESS, moved.status()); assertEquals(moved, management.action(created.complaintId(), escalation, Operation.ESCALATE));
      assertEquals(started, management.action(created.complaintId(), action("start", 0), Operation.START_PROCESSING));
      assertThrows(IllegalArgumentException.class, () -> management.detail(created.complaintId()));
      assertEquals(0, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
    }
    try (var auth = employeeContext(AUTHORITY, SELLER)) {
      var row = management.detail(created.complaintId()); assertEquals("final", row.complaint().queueId()); assertNull(row.complaint().assigneeUserId());
      assertEquals(INVOICE, row.invoice().invoiceId()); assertEquals(SELLER, row.invoice().tenantId());
      var last = management.history(created.complaintId(), 0, 100).getContent().get(2);
      assertEquals(EventType.COMPLAINT_ESCALATED, last.eventType()); assertEquals("review", last.fromQueueId()); assertEquals("final", last.toQueueId()); assertEquals(HANDLER, last.actorId());
      assertThrows(BadRequestException.class, () -> management.action(created.complaintId(), action("no-next", row.complaint().version()), Operation.ESCALATE));
    }
    assertEquals(3, count(ComplaintEvent.class)); assertEquals(3, count(ComplaintOutbox.class));
  }
  @Test void processingInboxUsesMongoScopedCountsPagesInvoiceRestrictionsAndExcludesInternal() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h);
    var first = guestCase(h, "first"); guestCase(h, "second"); workflow.submit(f.submit(Kind.INTERNAL, "legacy-internal"));
    try (var auth = customerContext(CUSTOMER)) { h.intake.platform(new ComplaintIntakeRequests.PlatformIssue("platform-inbox", "SYSTEM_ERROR", "Issue", "Description", null)); }
    try (var auth = employeeContext(HANDLER, SELLER)) {
      var page = management.list(0, 1, Kind.INVOICE, Status.SUBMITTED, f.clock.instant(), f.clock.instant(), "complaintCode", Sort.Direction.ASC);
      assertEquals(2, page.getTotalElements()); assertEquals(1, page.getContent().size()); assertEquals(2, page.getTotalPages());
      assertThrows(BadRequestException.class, () -> management.list(0, 10, Kind.INTERNAL, null, null, null, "createdAt", Sort.Direction.DESC));
      assertThrows(BadRequestException.class, () -> management.list(0, 10, null, null, null, null, "reporterUserId", Sort.Direction.DESC));
      assertThrows(IllegalArgumentException.class, () -> management.detail(complaints.query(new ReporterScope(ActorType.EMPLOYEE, EMPLOYEE, SELLER), Filter.all(), PageRequest.of(0, 1)).getContent().get(0).getIdAsString()));
    }
    var grants = new ArrayList<>(h.config.grants()); grants.removeIf(g -> g.userId().equals(HANDLER));
    grants.add(new ComplaintRoutingCatalog.ScopeGrant(HANDLER, "review", Set.of(Kind.INVOICE), Set.of(SELLER), false, false, Set.of(CASE)));
    h.importer.install(new ComplaintRoutingCatalog.Configuration(2, h.config.approvedBy(), h.config.categories(), h.config.queues(), h.config.memberships(), h.config.policies(), grants));
    try (var auth = employeeContext(HANDLER, SELLER)) {
      assertEquals(0, management.list(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC).getTotalElements());
      assertThrows(IllegalArgumentException.class, () -> management.detail(first.complaintId()));
      assertThrows(ForbiddenException.class, () -> management.action(first.complaintId(), action("no-scope", 0), Operation.START_PROCESSING));
    }
    try (var auth = employeeContext(HANDLER, PORTAL)) { assertThrows(ForbiddenException.class, () -> management.detail(first.complaintId())); }
  }
  @Test void processingRechecksLivePermissionsAndAssigneeMembership() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var created = guestCase(h, "authz");
    try (var auth = employeeContext(HANDLER, SELLER)) {
      assertThrows(ForbiddenException.class, () -> management.assign(created.complaintId(), new ComplaintProcessingContracts.Assignment("foreign-assignee", 0L, "Wrong queue", PLATFORM_HANDLER)));
      h.employeeOverrides.put(SELLER + ":" + HANDLER, new ComplaintDirectorySources.EmployeeFacts(true, Set.of(Permission.COMPLAINT_READ)));
      assertThrows(ForbiddenException.class, () -> management.action(created.complaintId(), action("revoked-permission", 0), Operation.START_PROCESSING));
      h.employeeOverrides.put(SELLER + ":" + HANDLER, new ComplaintDirectorySources.EmployeeFacts(false, EnumSet.allOf(Permission.class)));
      assertThrows(ForbiddenException.class, () -> management.detail(created.complaintId()));
    }
    assertEquals(0, complaints.findCommitted(created.complaintId()).orElseThrow().getVersion()); assertEquals(1, count(ComplaintEvent.class));
  }
  @Test void processingConcurrentSameEscalationHasOneHistoryAndConflictingMutationLosesVersionRace() throws Exception {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var created = guestCase(h, "parallel-escalate");
    try (var auth = employeeContext(HANDLER, SELLER)) { management.action(created.complaintId(), action("start", 0), Operation.START_PROCESSING); }
    var pool = Executors.newFixedThreadPool(4);
    try {
      var gate = new CountDownLatch(1); var futures = new ArrayList<Future<CommandResult>>();
      for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { gate.await(); try (var auth = employeeContext(HANDLER, SELLER)) {
        return management.action(created.complaintId(), action("same-escalation", 1), Operation.ESCALATE); } }));
      gate.countDown(); var first = futures.get(0).get(30, TimeUnit.SECONDS); for (var future : futures) assertEquals(first, future.get(30, TimeUnit.SECONDS));
      projection.recover(); assertEquals(3, count(ComplaintEvent.class)); assertEquals(3, count(ComplaintOutbox.class)); assertEquals("final", get(first).getCurrentQueueId());
      try (var auth = employeeContext(AUTHORITY, SELLER)) {
        assertThrows(OptimisticLockingFailureException.class, () -> management.action(created.complaintId(), action("stale-resolve", 1), Operation.RESOLVE));
      }
    } finally { pool.shutdownNow(); }
  }
  @Test void processingAssignmentAndEscalationProjectionFailuresRecoverWithoutDuplicateEvents() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var created = guestCase(h, "projection-processing");
    try (var auth = employeeContext(HANDLER, SELLER)) {
      doThrow(new DataAccessResourceFailureException("injected assignment history failure")).doCallRealMethod().when(events).append(any());
      var assigned = management.assign(created.complaintId(), new ComplaintProcessingContracts.Assignment("assigned", 0L, "Assign self", HANDLER));
      assertNotNull(get(assigned).getPendingCommit()); assertEquals(HANDLER, get(assigned).getAssigneeUserId()); assertEquals(1, count(ComplaintEvent.class));
      f.clock.advance(10); projection.recover(); assertNull(get(assigned).getPendingCommit());
      management.action(created.complaintId(), action("started", 1), Operation.START_PROCESSING);
      doThrow(new DataAccessResourceFailureException("injected outbox failure")).doCallRealMethod().when(outbox).append(any());
      var moved = management.action(created.complaintId(), action("moved", 2), Operation.ESCALATE);
      assertEquals("final", get(moved).getCurrentQueueId()); assertNotNull(get(moved).getPendingCommit()); assertEquals(4, count(ComplaintEvent.class)); assertEquals(3, count(ComplaintOutbox.class));
      f.clock.advance(10); projection.recover(); assertNull(get(moved).getPendingCommit()); assertEquals(4, count(ComplaintEvent.class)); assertEquals(4, count(ComplaintOutbox.class));
      assertEquals(moved, management.action(created.complaintId(), action("moved", 2), Operation.ESCALATE));
    }
  }
  @Test void processingRootWriteFailureCannotEmitAssignmentHistoryOrOutbox() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var created = guestCase(h, "root-failure");
    doThrow(new DataAccessResourceFailureException("injected write failure")).when(complaints).changeProcessing(any(), any(), any(), any(), any(), any());
    try (var auth = employeeContext(HANDLER, SELLER)) {
      assertThrows(DataAccessResourceFailureException.class, () -> management.assign(created.complaintId(), new ComplaintProcessingContracts.Assignment("fail", 0L, "Assign self", HANDLER)));
    }
    assertEquals(0, complaints.findCommitted(created.complaintId()).orElseThrow().getVersion()); assertEquals(1, count(ComplaintEvent.class)); assertEquals(1, count(ComplaintOutbox.class));
  }
  private static final String MASTER = "000000000000000000000004", MASTER_HANDLER = "000000000000000000000017";
  private void higherPolicy(ComplaintIntakeHarness h, boolean subTenant) {
    var queues = new ArrayList<>(h.config.queues()); var members = new ArrayList<>(h.config.memberships()); var grants = new ArrayList<>(h.config.grants());
    queues.add(new ComplaintQueue("platform-review", "Platform Review", new ProcessingScope(Boundary.PLATFORM, PLATFORM), QueuePurpose.PROCESSING, true));
    members.add(new ComplaintQueueMembership("platform-review-member", "platform-review", PLATFORM_HANDLER, true, EnumSet.allOf(QueueCapability.class), Authority.NONE));
    grants.removeIf(g -> g.queueId().equals("platform-support"));
    for (String queue : List.of("platform-review", "platform-support")) grants.add(new ComplaintRoutingCatalog.ScopeGrant(PLATFORM_HANDLER, queue, Set.of(Kind.INVOICE, Kind.PLATFORM), Set.of(SELLER), true, true, Set.of()));
    if (subTenant) {
      h.tenantOverrides.put(SELLER, new TenantInfo(SELLER, TenantStructure.SUB_TENANT, MASTER, false, true));
      h.tenantOverrides.put(MASTER, new TenantInfo(MASTER, TenantStructure.MASTER_TENANT, null, false, true));
      h.employeeOverrides.put(MASTER + ":" + MASTER_HANDLER, new ComplaintDirectorySources.EmployeeFacts(true, EnumSet.allOf(Permission.class)));
      queues.add(new ComplaintQueue("master-final", "Master Final Authority", new ProcessingScope(Boundary.TENANT, MASTER), QueuePurpose.FINAL_AUTHORITY, true));
      members.add(new ComplaintQueueMembership("master-final-member", "master-final", MASTER_HANDLER, true, EnumSet.allOf(QueueCapability.class), Authority.TENANT_AUTHORITY));
      grants.add(new ComplaintRoutingCatalog.ScopeGrant(MASTER_HANDLER, "master-final", Set.of(Kind.INVOICE), Set.of(SELLER), false, true, Set.of()));
    }
    var policies = new ArrayList<ComplaintRoutingPolicy>();
    for (var old : h.config.policies()) {
      if (old.kind() == Kind.INTERNAL) { policies.add(old); continue; }
      policies.add(new ComplaintRoutingPolicy(old.id(), 1, old.ownerTenantId(), old.kind(), old.categoryCodes(), old.queueIds(), old.edges(), old.reporterOriginQueueIds(), old.partnerBindings(), old.platformInitialQueueId(), Publication.RETIRED, false, old.publishedAt(), old.publishedBy()));
      var nodes = new HashSet<String>(); var edges = new ArrayList<ComplaintRoutingPolicy.Edge>();
      nodes.addAll(List.of("platform-review", "platform-support")); edges.add(new ComplaintRoutingPolicy.Edge("platform-review", "platform-support"));
      if (old.kind() == Kind.INVOICE) {
        nodes.addAll(List.of("review", "final")); edges.add(new ComplaintRoutingPolicy.Edge("review", "final"));
        if (subTenant) { nodes.add("master-final"); edges.add(new ComplaintRoutingPolicy.Edge("final", "master-final")); edges.add(new ComplaintRoutingPolicy.Edge("master-final", "platform-review")); }
        else edges.add(new ComplaintRoutingPolicy.Edge("final", "platform-review"));
      }
      policies.add(new ComplaintRoutingPolicy(old.id(), 2, old.ownerTenantId(), old.kind(), old.categoryCodes(), nodes, edges, Set.of(), old.partnerBindings(),
          old.kind() == Kind.PLATFORM ? "platform-review" : null, Publication.PUBLISHED, true, old.publishedAt(), old.publishedBy()));
    }
    h.importer.install(new ComplaintRoutingCatalog.Configuration(2, h.config.approvedBy(), h.config.categories(), queues, members, policies, grants));
  }
  @Test void processingSubTenantEscalatesThroughOwnFinalMasterFinalAndPlatformWithScopedHandoff() {
    var h = new ComplaintIntakeHarness(this); higherPolicy(h, true); var management = processing(h); var created = guestCase(h, "master-route");
    try (var auth = employeeContext(HANDLER, SELLER)) {
      management.action(created.complaintId(), action("start", 0), Operation.START_PROCESSING); management.action(created.complaintId(), action("to-final", 1), Operation.ESCALATE);
    }
    try (var auth = employeeContext(AUTHORITY, SELLER)) { management.action(created.complaintId(), action("to-master", 2), Operation.ESCALATE); }
    try (var auth = employeeContext(MASTER_HANDLER, MASTER)) {
      assertEquals("master-final", management.detail(created.complaintId()).complaint().queueId());
      var moved = management.action(created.complaintId(), action("to-platform", 3), Operation.ESCALATE);
      assertEquals(moved, management.action(created.complaintId(), action("to-platform", 3), Operation.ESCALATE));
      assertThrows(IllegalArgumentException.class, () -> management.detail(created.complaintId()));
    }
    try (var auth = employeeContext(PLATFORM_HANDLER, PLATFORM)) {
      var row = management.detail(created.complaintId()); assertEquals(SELLER, row.invoice().tenantId()); assertEquals(PLATFORM, row.complaint().processingTenantId()); assertEquals("platform-review", row.complaint().queueId());
      var moved = management.action(created.complaintId(), action("to-platform-final", row.complaint().version()), Operation.ESCALATE);
      var resolved = management.action(created.complaintId(), action("resolved-platform", moved.version()), Operation.RESOLVE); assertEquals(Status.RESOLVED, resolved.status());
    }
  }
  @Test void processingPlatformRemainsWithinPlatformAndStandaloneSellerCanEscalateByConfiguredPolicy() {
    var h = new ComplaintIntakeHarness(this); higherPolicy(h, false); var management = processing(h); String platformCase;
    try (var auth = customerContext(CUSTOMER)) { platformCase = h.intake.platform(new ComplaintIntakeRequests.PlatformIssue("system", "SYSTEM_ERROR", "System issue", "Description", null)).complaintId(); }
    try (var auth = employeeContext(HANDLER, SELLER)) { assertThrows(IllegalArgumentException.class, () -> management.detail(platformCase)); }
    try (var auth = employeeContext(PLATFORM_HANDLER, PLATFORM)) {
      assertEquals("platform-review", management.detail(platformCase).complaint().queueId());
      management.action(platformCase, action("start", 0), Operation.START_PROCESSING); var moved = management.action(platformCase, action("higher", 1), Operation.ESCALATE);
      var row = management.detail(platformCase); assertEquals(Boundary.PLATFORM, row.complaint().processingBoundary()); assertEquals("platform-support", row.complaint().queueId()); assertNull(row.invoice());
      assertThrows(BadRequestException.class, () -> management.action(platformCase, action("no-more", moved.version()), Operation.ESCALATE));
    }
    var invoiceCase = guestCase(h, "standalone");
    try (var auth = employeeContext(HANDLER, SELLER)) { management.action(invoiceCase.complaintId(), action("invoice-start", 0), Operation.START_PROCESSING); management.action(invoiceCase.complaintId(), action("to-final", 1), Operation.ESCALATE); }
    try (var auth = employeeContext(AUTHORITY, SELLER)) { management.action(invoiceCase.complaintId(), action("to-platform", 2), Operation.ESCALATE); }
    try (var auth = employeeContext(PLATFORM_HANDLER, PLATFORM)) { assertEquals("platform-review", management.detail(invoiceCase.complaintId()).complaint().queueId()); }
  }
  @Test void processingEscalationRequiresTargetCaseScopeAndNeverUsesNewPolicyToSkipPinnedRoute() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var created = guestCase(h, "pinned-policy"); higherPolicy(h, false);
    try (var auth = employeeContext(HANDLER, SELLER)) { management.action(created.complaintId(), action("start", 0), Operation.START_PROCESSING); management.action(created.complaintId(), action("final", 1), Operation.ESCALATE); }
    try (var auth = employeeContext(AUTHORITY, SELLER)) {
      assertEquals(1, management.detail(created.complaintId()).routingPolicyVersion());
      assertThrows(BadRequestException.class, () -> management.action(created.complaintId(), action("cannot-use-v2", 2), Operation.ESCALATE));
    }
    var next = guestCase(h, "target-no-scope");
    var config = mongo.findById("active", ComplaintRoutingCatalog.class).configuration();
    h.importer.install(new ComplaintRoutingCatalog.Configuration(3, config.approvedBy(), config.categories(), config.queues(), config.memberships(), config.policies(), config.grants().stream().filter(g -> !g.queueId().equals("final")).toList()));
    try (var auth = employeeContext(HANDLER, SELLER)) {
      management.action(next.complaintId(), action("start-next", 0), Operation.START_PROCESSING);
      assertThrows(ForbiddenException.class, () -> management.action(next.complaintId(), action("blocked-target", 1), Operation.ESCALATE));
      assertEquals("review", management.detail(next.complaintId()).complaint().queueId()); assertEquals(1, management.detail(next.complaintId()).complaint().version());
    }
  }
  @Test void backofficeSearchUsesTrueInvoiceAndScopedMongoPagingWithLiteralSearch() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var first = guestCase(h, "search-first"); guestCase(h, "search-second");
    try (var auth = employeeContext(HANDLER, SELLER)) {
      var search = new ComplaintProcessingContracts.Search(null, "original", "SERVICE_ISSUE", "review");
      var page = management.search(0, 1, Kind.INVOICE, Status.SUBMITTED, f.clock.instant(), f.clock.instant(), "complaintCode", Sort.Direction.ASC, search);
      assertEquals(2, page.getTotalElements()); assertEquals(1, page.getContent().size()); assertEquals("INV-ORIGINAL", page.getContent().get(0).invoiceCode());
      assertEquals(1, management.search(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC, new ComplaintProcessingContracts.Search(first.complaintCode(), null, null, null)).getTotalElements());
      assertEquals(0, management.search(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC, new ComplaintProcessingContracts.Search(null, ".*", null, null)).getTotalElements());
      assertEquals(0, management.search(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC, new ComplaintProcessingContracts.Search(null, null, null, "platform-support")).getTotalElements());
      assertEquals(Boundary.TENANT, management.options().boundary()); assertEquals(List.of("review"), management.options().queues().stream().map(ComplaintProcessingContracts.Choice::value).toList());
      var detail = management.detail(first.complaintId()); assertEquals("INV-ORIGINAL", detail.invoiceData().invoiceCode()); assertEquals("Milk", detail.invoiceData().items().get(0).productName());
    }
  }
  @Test void backofficeCapabilitiesAreCaseSpecificAndReadOnlyUsersCannotSeeActionsOrCandidates() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var created = guestCase(h, "capabilities");
    try (var auth = employeeContext(HANDLER, SELLER)) {
      var caps = management.detail(created.complaintId()).capabilities(); assertEquals(0, caps.version()); assertTrue(caps.allowedActions().containsAll(List.of("assign", "start"))); assertEquals(HANDLER, caps.assignees().get(0).userId());
      assertFalse(caps.allowedActions().contains("escalate")); management.action(created.complaintId(), action("start", 0), Operation.START_PROCESSING);
      assertTrue(management.detail(created.complaintId()).capabilities().allowedActions().containsAll(List.of("resolve", "request-information", "escalate")));
      h.employeeOverrides.put(SELLER + ":" + HANDLER, new ComplaintDirectorySources.EmployeeFacts(true, Set.of(Permission.COMPLAINT_READ)));
      var readonly = management.detail(created.complaintId()).capabilities(); assertTrue(readonly.allowedActions().isEmpty()); assertTrue(readonly.assignees().isEmpty());
      assertThrows(ForbiddenException.class, () -> management.action(created.complaintId(), action("deny-resolve", 1), Operation.RESOLVE));
    }
    assertEquals(2, count(ComplaintEvent.class)); assertEquals(2, count(ComplaintOutbox.class));
  }
  @Test void backofficeInvoiceJoinCannotReturnInvoiceWhoseSellerBindingChanged() {
    var h = new ComplaintIntakeHarness(this); var management = processing(h); var created = guestCase(h, "binding");
    mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(INVOICE))), new Update().set("tenantId", PORTAL), Invoice.class);
    try (var auth = employeeContext(HANDLER, SELLER)) {
      assertNull(management.detail(created.complaintId()).invoiceData());
      assertEquals(0, management.search(0, 10, null, null, null, null, "createdAt", Sort.Direction.DESC, new ComplaintProcessingContracts.Search(null, "INV-ORIGINAL", null, null)).getTotalElements());
    }
  }
  @Test void reporterCustomerSearchFiltersAndPaginationNeverIncludeGuestOrAnotherCustomer() {
    var h = new ComplaintIntakeHarness(this); guestCase(h, "guest-not-customer"); String own;
    try (var auth = customerContext(CUSTOMER)) {
      own = h.intake.customerInvoice(new ComplaintIntakeRequests.CustomerInvoice(INVOICE, "reporter-invoice", "SERVICE_ISSUE", "Invoice support", "Description", null)).complaintId();
      h.intake.platform(new ComplaintIntakeRequests.PlatformIssue("reporter-platform", "SYSTEM_ERROR", "Portal support", "Description", null));
      assertEquals(2, h.intake.customerMine(0, 1, null, Status.SUBMITTED, "support", f.clock.instant(), f.clock.instant()).getTotalElements());
      assertEquals(1, h.intake.customerMine(0, 1, Kind.PLATFORM, null, "Portal", null, null).getTotalElements());
      assertEquals(0, h.intake.customerMine(0, 10, null, null, ".*", null, null).getTotalElements());
      assertEquals(1, h.intake.customerMine(1, 1, null, null, null, null, null).getContent().size());
      assertThrows(BadRequestException.class, () -> h.intake.customerMine(0, 10, Kind.INTERNAL, null, null, null, null));
      assertThrows(BadRequestException.class, () -> h.intake.customerMine(0, 10, null, null, "a".repeat(129), null, null));
      assertThrows(BadRequestException.class, () -> h.intake.customerMine(0, 10, null, null, null, f.clock.instant().plusSeconds(1), f.clock.instant()));
    }
    var other = new com.ecofood.app.userACL.entity.UserEntity(); other.setId(new ObjectId(OTHER)); when(h.customers.requireSelfServiceAccount(anyString())).thenReturn(other);
    try (var auth = customerContext(OTHER)) {
      assertEquals(0, h.intake.customerMine(0, 10, null, null, "support", null, null).getTotalElements());
      assertThrows(IllegalArgumentException.class, () -> h.intake.customerDetail(own));
      assertThrows(IllegalArgumentException.class, () -> h.intake.customerInvoice(new ComplaintIntakeRequests.CustomerInvoice(INVOICE, "foreign-claim", "SERVICE_ISSUE", "Issue", "Description", null)));
    }
  }
  @Test void reporterCustomerDetailReturnsPublicResponsesWithoutInternalNotesOrEmployeeMetadata() throws Exception {
    var h = new ComplaintIntakeHarness(this); String id;
    try (var auth = customerContext(CUSTOMER)) { id = h.intake.platform(new ComplaintIntakeRequests.PlatformIssue("public-customer", "SYSTEM_ERROR", "Portal issue", "Description", null)).complaintId(); }
    var submitted = complaints.findCommitted(id).orElseThrow().getSubmissionResult();
    var actor = f.actor(PLATFORM_HANDLER, PLATFORM);
    var started = h.workflow.startProcessing(new WorkflowCommand(id, submitted.version(), "private-start", "trace", actor, null, "PRIVATE_STAFF_NOTE", Visibility.INTERNAL));
    h.workflow.requestAdditionalInformation(new WorkflowCommand(id, started.version(), "public-request", "trace", actor, null, "PUBLIC_REPLY_FOR_CUSTOMER", Visibility.PUBLIC));
    try (var auth = customerContext(CUSTOMER)) {
      var detail = h.intake.customerDetail(id); assertEquals(Status.WAITING_REPORTER, detail.complaint().status()); assertEquals(2, detail.history().size());
      var page = h.intake.customerDetail(id, 1, 1); assertEquals(1, page.history().size()); assertEquals(2, page.historyTotalPages()); assertEquals(2, page.historyTotalElements()); assertEquals("PUBLIC_REPLY_FOR_CUSTOMER", page.history().get(0).reason());
      assertThrows(BadRequestException.class, () -> h.intake.customerDetail(id, 0, 101));
      var json = new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(detail);
      assertTrue(json.contains("PUBLIC_REPLY_FOR_CUSTOMER"));
      for (String secret : List.of("PRIVATE_STAFF_NOTE", "actorId", PLATFORM_HANDLER, "fromQueueId", "processingTenantId", "commandKey", "payloadFingerprint")) assertFalse(json.contains(secret));
    }
  }
  @Test void reporterGuestTracksPublicResponsesOnlyWithSeparateProofAndCannotUseReceiptAsProof() {
    var h = new ComplaintIntakeHarness(this);
    var created = h.intake.guest(h.token, new ComplaintIntakeRequests.InvoiceIssue("public-guest", "SERVICE_ISSUE", "Service issue", "Description", null));
    var id = created.complaint().complaintId(); var proof = created.trackingUrl().split("#proof=")[1].split("\\.")[1];
    var started = h.workflow.startProcessing(new WorkflowCommand(id, 0, "private-start", "trace", f.handler(), null, "PRIVATE_GUEST_STAFF_NOTE", Visibility.INTERNAL));
    h.workflow.requestAdditionalInformation(new WorkflowCommand(id, started.version(), "public-request", "trace", f.handler(), null, "PUBLIC_REPLY_FOR_GUEST", Visibility.PUBLIC));
    var detail = h.intake.track(new ComplaintIntakeRequests.Track(id, proof)); assertEquals(2, detail.history().size());
    assertTrue(detail.history().stream().anyMatch(event -> event.reason().equals("PUBLIC_REPLY_FOR_GUEST")));
    assertFalse(detail.history().stream().anyMatch(event -> event.reason().equals("PRIVATE_GUEST_STAFF_NOTE")));
    var page = h.intake.track(new ComplaintIntakeRequests.Track(id, proof), 1, 1); assertEquals(2, page.historyTotalElements()); assertEquals(1, page.history().size()); assertEquals("PUBLIC_REPLY_FOR_GUEST", page.history().get(0).reason());
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> h.intake.track(new ComplaintIntakeRequests.Track(id, h.token)));
    h.proof.revoke(id); assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> h.intake.track(new ComplaintIntakeRequests.Track(id, proof)));
  }
}
