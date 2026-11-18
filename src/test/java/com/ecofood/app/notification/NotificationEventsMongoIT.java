package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.customer.auth.*;
import com.ecofood.app.customer.claim.*;
import com.ecofood.app.customer.claim.InvoiceClaimContracts.*;
import com.ecofood.app.customer.dto.CustomerProfileUpdateRequest;
import com.ecofood.app.customer.entity.CustomerProfileEntity;
import com.ecofood.app.customer.repository.*;
import com.ecofood.app.customer.service.CustomerPortalService;
import com.ecofood.app.notification.NotificationContracts.*;
import com.ecofood.app.receipt.config.ReceiptProperties;
import com.ecofood.app.receipt.entity.ReceiptAccess;
import com.ecofood.app.receipt.service.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.security.AuthenticationContext;
import com.mongodb.*;
import com.mongodb.client.*;
import com.nimbusds.jwt.JWTClaimsSet;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

/** Opt-in real Mongo persistence test. All accesses are redirected to UUID-owned test collections. */
class NotificationEventsMongoIT {
  @Test void businessWritesOutboxRetryAndDeliveryPersistWithoutAffectingProductionCollections() throws Exception {
    var properties = new Properties();
    if (Files.exists(Path.of(".env"))) try (var reader = Files.newBufferedReader(Path.of(".env"))) { properties.load(reader); }
    String uri = System.getenv("MONGODB_URI"); if (uri == null || uri.isBlank()) uri = properties.getProperty("MONGODB_URI");
    assertNotNull(uri, "Mongo URI required for opt-in integration test");
    String database = System.getenv("MONGODB_DATABASE"); if (database == null || database.isBlank()) database = properties.getProperty("MONGODB_DATABASE", "appdb");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    var collections = new LinkedHashMap<String, String>();
    for (String source : List.of("notification", "notification_preferences", "customer_invoice_link", "invoice_claim_proof", "app_user", "customer_profile", "notification_email_capture"))
      collections.put(source, "notification_events_test_" + source + "_" + suffix);
    var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
        .applyToClusterSettings(builder -> builder.serverSelectionTimeout(10, TimeUnit.SECONDS)).build();
    try (var client = MongoClients.create(settings)) {
      MongoDatabase actual = client.getDatabase(database);
      var names = actual.listCollectionNames().into(new ArrayList<String>());
      assertTrue(collections.values().stream().noneMatch(names::contains));
      try {
        var isolated = (MongoDatabase) java.lang.reflect.Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(), new Class<?>[]{MongoDatabase.class}, (proxy, method, args) -> {
          if (method.getName().equals("drop")) throw new SecurityException("Database drop forbidden");
          if (method.getName().equals("getCollection")) {
            var mapped = args.clone(); mapped[0] = collections.get((String) args[0]);
            if (mapped[0] == null) throw new SecurityException("Unexpected collection");
            return method.invoke(actual, mapped);
          }
          return method.invoke(actual, args);
        });
        var base = new SimpleMongoClientDatabaseFactory(client, database);
        var factory = (org.springframework.data.mongodb.MongoDatabaseFactory) java.lang.reflect.Proxy.newProxyInstance(
            org.springframework.data.mongodb.MongoDatabaseFactory.class.getClassLoader(), new Class<?>[]{org.springframework.data.mongodb.MongoDatabaseFactory.class}, (proxy, method, args) -> {
              if (method.getName().equals("getMongoDatabase")) return isolated;
              return method.invoke(base, args);
            });
        exercise(new MongoTemplate(factory));
      } finally {
        for (String name : collections.values()) {
          assertTrue(name.matches("notification_events_test_[a-z_]+_[a-f0-9]{32}"), "Unsafe cleanup target");
          actual.getCollection(name).drop();
        }
        AuthenticationContext.unload();
        System.out.println("NOTIFICATION_EVENTS_MONGO CLEANUP: owned UUID collections removed");
      }
    }
  }

  void exercise(MongoTemplate mongo) throws Exception {
    new NotificationMongoConfig().notificationIndexes(mongo).run(null);
    new NotificationDeliveryConfig().notificationDeliveryIndexes(mongo).run(null);
    new InvoiceClaimMongoConfig().invoiceClaimIndexes(mongo).run(null);
    mongo.indexOps(CustomerProfileEntity.class).createIndex(new Index().on("tenant_identifier", Sort.Direction.ASC).on("user_identifier", Sort.Direction.ASC).unique());
    var repositories = new MongoRepositoryFactory(mongo);
    var notes = repositories.getRepository(NotificationRepository.class);
    var prefs = repositories.getRepository(NotificationPreferenceRepository.class);
    var users = repositories.getRepository(UserRepository.class,
        org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments.just(
            new com.ecofood.app.userACL.repository.UserRepositoryImpl(mongo)));
    var links = repositories.getRepository(CustomerInvoiceLinkRepository.class);
    var proofs = repositories.getRepository(InvoiceClaimProofRepository.class);
    var profiles = repositories.getRepository(CustomerProfileRepository.class);
    var now = new MutableClock();
    String portalId = new ObjectId().toHexString(), seller = new ObjectId().toHexString(), invoiceId = new ObjectId().toHexString();
    var resolver = mock(CustomerTenantResolver.class);
    var portal = new CustomerTenantResolver.Portal(portalId, "Test", "test", "test-public", "https://identity.example.test/realms/test");
    when(resolver.resolve()).thenReturn(portal);
    var keycloak = mock(CustomerKeycloakAccountService.class);
    var provisioning = new CustomerIdentityService(users, mongo, resolver, keycloak);
    var login = new JWTClaimsSet.Builder().issuer(portal.issuer()).subject("test-subject").claim("azp", portal.clientId())
        .claim("email", "verified@example.test").claim("email_verified", true).claim("typ", "Bearer").build();
    var user = provisioning.provision(login); String customer = user.getIdAsString();
    assertEquals(1, user.getNotificationOutbox().size());
    assertEquals(customer, provisioning.provision(login).getIdAsString()); assertEquals(1, users.count());
    var identities = mock(CustomerIdentityService.class);
    when(identities.requireAccount(customer)).thenAnswer(call -> users.findById(user.getId()).orElseThrow());
    when(identities.requireSelfServiceAccount(anyString())).thenAnswer(call -> users.findById(user.getId()).orElseThrow());
    when(identities.requirePurchaseSession()).thenReturn(new CustomerIdentityService.CustomerSession(customer, portalId));
    var purchases = mock(CustomerPurchaseRepository.class);
    var invoice = new Invoice(); invoice.setId(invoiceId); invoice.setTenantId(seller); invoice.setDistributionPartnerId("TEST-SHOP"); invoice.setInvoiceNumber("TEST-INV"); invoice.setStatus(TransactionStatus.ACCEPTED);
    invoice.setItems(List.of());
    when(purchases.detail(new CustomerIdentityService.CustomerSession(customer, portalId), invoiceId)).thenAnswer(call -> links.findByInvoiceId(invoiceId).isPresent() ? Optional.of(invoice) : Optional.empty());
    var preferences = new NotificationPreferenceService(identities, prefs, now);
    var service = new NotificationService(identities, resolver, purchases, preferences, notes, mongo, now, mock(com.ecofood.app.complaint.ComplaintNotificationSource.class));
    var handler = new NotificationEventHandler(service);
    var outbox = new NotificationOutboxWorker(mongo, handler, now);
    // First verified Customer provisioning automatically yields one account event, never one per login.
    now.value = Instant.now().plusSeconds(1); outbox.poll(); assertEquals(2, notes.count());
    assertTrue(users.findById(user.getId()).orElseThrow().getNotificationOutbox().isEmpty());
    outbox.poll(); assertEquals(2, notes.count());

    var receiptService = mock(ReceiptService.class); var tenantQuery = mock(CoreTenantQueryClient.class);
    when(tenantQuery.getById(seller)).thenReturn(TenantResponse.builder().id(seller).build());
    var access = new ReceiptAccess(); access.setId("test-receipt"); access.setTokenHash("hash"); access.setExpiresAt(now.instant().plusSeconds(3600));
    var validated = new ReceiptService.ValidatedReceipt(invoice, access);
    when(receiptService.requireValidReceipt("a".repeat(43))).thenReturn(validated);
    when(receiptService.requireValidClaimReceipt(access.getId(), access.getTokenHash())).thenReturn(validated);
    var claim = new InvoiceClaimService(identities, resolver, receiptService, new ReceiptTokenCodec(new ReceiptProperties()), tenantQuery, links, proofs, now);
    String secret = Base64.getEncoder().encodeToString("notification-integration-test-key-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String jwt = Jwts.builder().subject(customer).claim("identityType", "CUSTOMER").claim("userCategory", "CUSTOMER")
        .claim("tenantId", portalId).claim("clientId", portal.clientId()).claim("permissions", List.of("CUSTOMER_INVOICE_CLAIM"))
        .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret))).compact();
    AuthenticationContext.setSecret(secret); AuthenticationContext.setToken("Bearer " + jwt);
    var proof = claim.eligibility(new ReceiptRequest("a".repeat(43))).claimProof();
    var claimed = claim.claim(new ClaimRequest(proof, true));
    assertEquals(claimed, claim.claim(new ClaimRequest(proof, true))); assertEquals(1, links.count());
    assertEquals(1, links.findByInvoiceId(invoiceId).orElseThrow().getNotificationOutbox().size());
    // Crash after IN_APP persistence: retry reuses it and completes EMAIL without creating a duplicate.
    var interrupted = spy(new NotificationEventHandler(service));
    doAnswer(call -> { NotificationOutboxEvent event = call.getArgument(0); service.create(event.channel(Channel.IN_APP)); throw new IllegalStateException("temporary"); }).when(interrupted).handle(any());
    new NotificationOutboxWorker(mongo, interrupted, now).poll();
    assertEquals(3, notes.count());
    var retained = links.findByInvoiceId(invoiceId).orElseThrow().getNotificationOutbox().get(0);
    assertEquals(1, retained.attempts()); assertEquals("EVENT_PROCESSING_FAILED", retained.lastError());
    assertEquals(customer, retained.customerId()); assertEquals(invoiceId, retained.referenceId());
    now.value = now.value.plusSeconds(10); outbox.poll(); assertEquals(4, notes.count()); assertTrue(links.findByInvoiceId(invoiceId).orElseThrow().getNotificationOutbox().isEmpty());
    claim.claim(new ClaimRequest(proof, true)); outbox.poll(); assertEquals(4, notes.count());
    var invoiceNotice = notes.findAll().stream().filter(n -> n.getType() == Type.TRANSACTIONAL && n.getDeliveryChannel() == Channel.IN_APP).findFirst().orElseThrow();
    assertEquals(customer, invoiceNotice.getRecipientCustomerId()); assertEquals(invoiceId, invoiceNotice.getActionTarget().resourceId());
    assertEquals(2, service.unreadCount(null).unreadCount());
    // No provider configured: durable email remains PENDING, Invoice claim remains successful.
    var adapters = new NotificationEmailAdapters();
    var disabled = new NotificationEmailWorker(mongo, service, identities, adapters.notificationEmailAdapter(mongo, now, "disabled"), now);
    disabled.poll(); assertTrue(notes.findAll().stream().filter(n -> n.getDeliveryChannel() == Channel.EMAIL).allMatch(n -> n.getDeliveryStatus() == DeliveryStatus.PENDING));
    var failing = mock(EmailDeliveryAdapter.class); when(failing.enabled()).thenReturn(true); when(failing.deliver(any())).thenThrow(new IllegalStateException("temporary provider failure"));
    new NotificationEmailWorker(mongo, service, identities, failing, now).deliverOne();
    assertEquals(claimed, claim.claim(new ClaimRequest(proof, true))); assertEquals(1, links.count());
    assertTrue(notes.findAll().stream().anyMatch(n -> "EMAIL_DELIVERY_FAILED".equals(n.getLastDeliveryError())));
    now.value = now.value.plusSeconds(60);
    var capture = adapters.notificationEmailAdapter(mongo, now, "capture");
    var delivery = new NotificationEmailWorker(mongo, service, identities, capture, now); delivery.poll();
    assertEquals(2, mongo.count(new Query(), "notification_email_capture"));
    assertTrue(notes.findAll().stream().filter(n -> n.getDeliveryChannel() == Channel.EMAIL).allMatch(n -> n.getDeliveryStatus() == DeliveryStatus.CAPTURED && n.getSentAt() == null));
    delivery.poll(); assertEquals(2, mongo.count(new Query(), "notification_email_capture"));
    var capturedNotice = notes.findAll().stream().filter(n -> n.getDeliveryStatus() == DeliveryStatus.CAPTURED).findFirst().orElseThrow();
    capture.deliver(new EmailDeliveryAdapter.Message(capturedNotice.getIdAsString(), customer, user.getEmail(), "Thông báo EcoFood", "Thông báo kiểm thử"));
    assertEquals(2, mongo.count(new Query(), "notification_email_capture")); // Provider accepted before process crash: same key is replay-safe.
    // Profile update pushes event + audit in the same atomic write; retry/no-op does not append.
    var profile = new CustomerPortalService(identities, profiles, mongo, now);
    profile.updateMyProfile(new CustomerProfileUpdateRequest("Test", null, null, null));
    profile.updateMyProfile(new CustomerProfileUpdateRequest("Test", null, null, null));
    assertEquals(1, profiles.findAll().get(0).getNotificationOutbox().size());
    assertEquals(1, profiles.findAll().get(0).getChangeHistory().size());
    outbox.poll(); assertEquals(6, notes.count());
    // Email change emits only when a fresh verified identity sync actually modifies the email.
    var changedLogin = new JWTClaimsSet.Builder(login).claim("email", "new-verified@example.test").build();
    provisioning.provision(changedLogin); provisioning.provision(changedLogin);
    assertEquals(1, users.findById(user.getId()).orElseThrow().getNotificationOutbox().size());
    now.value = Instant.now().plusSeconds(120); outbox.poll(); assertEquals(8, notes.count());
    preferences.updateMine(new PreferenceRequest(null, null, null, true, true));
    var marketing = service.create(new Event("test-marketing-withdrawal", customer, seller, "TEST-SHOP", Type.MARKETING, ReferenceType.INVOICE, invoiceId, Channel.EMAIL));
    assertEquals(DeliveryStatus.PENDING, marketing.deliveryStatus());
    preferences.updateMine(new PreferenceRequest(null, null, null, null, false));
    delivery.poll();
    assertEquals(DeliveryStatus.SUPPRESSED, notes.findById(new ObjectId(marketing.notificationId())).orElseThrow().getDeliveryStatus());
    assertNull(mongo.findById(marketing.notificationId(), Document.class, "notification_email_capture"));
    assertEquals(TransactionStatus.ACCEPTED, invoice.getStatus());
    System.out.println("NOTIFICATION_EVENTS_MONGO PASS: provisioning, claim, embedded atomic events, replay after partial failure, retry, disabled email, capture, profile and verified email sync");
  }
  static class MutableClock extends Clock {
    Instant value = Instant.now(); public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(java.time.ZoneId zone) { return this; } public Instant instant() { return value; }
  }
}
