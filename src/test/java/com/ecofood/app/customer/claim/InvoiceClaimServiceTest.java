package com.ecofood.app.customer.claim;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.customer.auth.CustomerTenantResolver;
import com.ecofood.app.customer.claim.InvoiceClaimContracts.*;
import com.ecofood.app.receipt.config.ReceiptProperties;
import com.ecofood.app.receipt.entity.ReceiptAccess;
import com.ecofood.app.receipt.service.ReceiptService;
import com.ecofood.app.receipt.service.ReceiptTokenCodec;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.security.AuthenticationContext;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;

class InvoiceClaimServiceTest {
  static final String PORTAL = "000000000000000000000001", SELLER = "000000000000000000000002";
  static final String INVOICE = "000000000000000000000003", A = "000000000000000000000011", B = "000000000000000000000012";
  static final String TOKEN = "a".repeat(43);
  static final String SECRET = Base64.getEncoder().encodeToString("test-invoice-claim-signing-secret-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
  final CustomerIdentityService identities = mock(CustomerIdentityService.class);
  final CustomerTenantResolver resolver = mock(CustomerTenantResolver.class);
  final ReceiptService receipts = mock(ReceiptService.class);
  final ReceiptTokenCodec codec = new ReceiptTokenCodec(new ReceiptProperties());
  final CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
  final CustomerInvoiceLinkRepository links = mock(CustomerInvoiceLinkRepository.class);
  final InvoiceClaimProofRepository proofs = mock(InvoiceClaimProofRepository.class);
  final Clock clock = mock(Clock.class);
  final InvoiceClaimService service = new InvoiceClaimService(identities, resolver, receipts, codec, tenants, links, proofs, clock);
  final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  final Map<String, InvoiceClaimProof> proofRows = new ConcurrentHashMap<>();
  final AtomicReference<CustomerInvoiceLink> owner = new AtomicReference<>();
  Invoice invoice;
  ReceiptAccess access;
  CountDownLatch concurrentInserts;

  @BeforeEach void setup() {
    when(clock.instant()).thenAnswer(call -> now.get()); when(clock.millis()).thenAnswer(call -> now.get().toEpochMilli());
    when(resolver.resolve()).thenReturn(new CustomerTenantResolver.Portal(PORTAL, "CustomerPortal", "portal", PORTAL + "-public", "https://idp.test/realms/portal"));
    when(identities.requireAccount(anyString())).thenAnswer(call -> { var user = new UserEntity(); user.setId(new ObjectId((String) call.getArgument(0))); return user; });
    when(tenants.getById(SELLER)).thenReturn(TenantResponse.builder().id(SELLER).build());
    invoice = new Invoice(); invoice.setId(INVOICE); invoice.setTenantId(SELLER); invoice.setPosId("pos");
    invoice.setDistributionPartnerId("partner"); invoice.setInvoiceNumber("INV-CLAIM"); invoice.setStatus(TransactionStatus.ACCEPTED);
    invoice.setCurrency("VND"); invoice.setTotalAmount(BigDecimal.TEN);
    invoice.setItems(List.of(new InvoiceItemDto("P1", "Product", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN)));
    access = new ReceiptAccess(); access.setId("a".repeat(64)); access.setInvoiceId(INVOICE); access.setTenantId(SELLER);
    access.setPosId("pos"); access.setDistributionPartnerId("partner"); access.setTokenHash(codec.hash(TOKEN)); access.setExpiresAt(NOW.plusSeconds(3600));
    var receipt = new ReceiptService.ValidatedReceipt(invoice, access);
    when(receipts.requireValidReceipt(TOKEN)).thenReturn(receipt);
    when(receipts.requireValidClaimReceipt(access.getId(), access.getTokenHash())).thenReturn(receipt);
    when(proofs.insert(any(InvoiceClaimProof.class))).thenAnswer(call -> { InvoiceClaimProof proof = call.getArgument(0); proofRows.put(proof.getId(), proof); return proof; });
    when(proofs.findByIdAndCustomerIdAndCustomerTenantId(anyString(), anyString(), anyString())).thenAnswer(call -> {
      var proof = proofRows.get(call.getArgument(0));
      return Optional.ofNullable(proof).filter(row -> row.getCustomerId().equals(call.getArgument(1)) && row.getCustomerTenantId().equals(call.getArgument(2)));
    });
    when(links.findByInvoiceId(INVOICE)).thenAnswer(call -> Optional.ofNullable(owner.get()));
    when(links.insert(any(CustomerInvoiceLink.class))).thenAnswer(call -> {
      var latch = concurrentInserts;
      if (latch != null) { latch.countDown(); if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Missing concurrent claim"); }
      CustomerInvoiceLink link = call.getArgument(0); if (link.getId() == null) link.setId(new ObjectId());
      if (!owner.compareAndSet(null, link)) throw new DuplicateKeyException("ux_customer_invoice_owner");
      return link;
    });
    authenticate(A, PORTAL, List.of("CUSTOMER_INVOICE_CLAIM"));
  }
  @AfterEach void clear() { AuthenticationContext.unload(); }

  static void authenticate(String customer, String tenant, List<String> permissions) {
    var key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET));
    String token = Jwts.builder().subject(customer).claim("tenantId", tenant).claim("clientId", PORTAL + "-public")
        .claim("identityType", "CUSTOMER").claim("userCategory", "CUSTOMER").claim("permissions", permissions)
        .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(key).compact();
    AuthenticationContext.setSecret(SECRET); AuthenticationContext.setToken("Bearer " + token);
  }
  Eligibility eligible() { return service.eligibility(new ReceiptRequest(TOKEN)); }
  ClaimRequest confirmation(String proof) { return new ClaimRequest(proof, true); }

  @Test void authenticatedPreviewCreatesOnlyHashBoundToCustomerAndReceiptWithoutClaiming() {
    var result = eligible();
    assertEquals(Status.ELIGIBLE, result.status()); assertEquals("INV-CLAIM", result.invoice().invoiceNumber());
    assertTrue(result.claimProof().matches("[A-Za-z0-9_-]{43}")); assertEquals(NOW.plusSeconds(300), result.proofExpiresAt());
    var stored = proofRows.values().iterator().next();
    assertEquals(codec.hash("invoice-claim-proof:v1:" + result.claimProof()), stored.getId());
    assertNotEquals(result.claimProof(), stored.getId()); assertEquals(A, stored.getCustomerId());
    assertEquals(SELLER, stored.getTenantId()); assertEquals(PORTAL, stored.getCustomerTenantId());
    assertEquals(codec.hash(TOKEN), stored.getReceiptTokenHash()); assertNull(owner.get());
  }

  @Test void confirmedClaimPreservesSellerTenantInvoiceAndStableAuditOnRetry() {
    var proof = eligible().claimProof();
    var first = service.claim(confirmation(proof)); now.set(NOW.plusSeconds(60));
    var second = service.claim(confirmation(proof));
    assertEquals(first, second); assertEquals(Status.CLAIMED_BY_YOU, first.status());
    assertEquals(A, owner.get().getCustomerId()); assertEquals(SELLER, owner.get().getTenantId());
    assertEquals(PORTAL, owner.get().getCustomerTenantId()); assertEquals("partner", owner.get().getDistributionPartnerId());
    assertEquals(A, owner.get().getCreatedBy()); assertEquals(NOW, owner.get().getClaimedAt());
    assertEquals(1, owner.get().getNotificationOutbox().size());
    var event = owner.get().getNotificationOutbox().get(0);
    assertEquals("invoice-claimed:" + first.linkId(), event.eventId());
    assertEquals(A, event.customerId()); assertEquals(INVOICE, event.referenceId()); assertEquals(SELLER, event.sourceTenantId());
    assertEquals(TransactionStatus.ACCEPTED, invoice.getStatus()); assertEquals(BigDecimal.TEN, invoice.getTotalAmount());
    assertNull(eligible().claimProof()); verify(links, times(1)).insert(any(CustomerInvoiceLink.class));
  }

  @Test void explicitConfirmationIsRequiredAndReceiptTokenIsNotAClaimProof() {
    var proof = eligible().claimProof();
    assertThrows(BadRequestException.class, () -> service.claim(new ClaimRequest(proof, false)));
    assertThrows(BadRequestException.class, () -> service.claim(new ClaimRequest(proof, null)));
    assertThrows(IllegalArgumentException.class, () -> service.claim(confirmation(TOKEN)));
    assertNull(owner.get());
  }

  @Test void otherCustomerCannotUseProofAndCannotSeeOwnerIdentityOrClaimTime() {
    String proofA = eligible().claimProof();
    authenticate(B, PORTAL, List.of("CUSTOMER_INVOICE_CLAIM"));
    assertThrows(IllegalArgumentException.class, () -> service.claim(confirmation(proofA)));
    String proofB = eligible().claimProof();
    authenticate(A, PORTAL, List.of("CUSTOMER_INVOICE_CLAIM")); service.claim(confirmation(proofA));
    authenticate(B, PORTAL, List.of("CUSTOMER_INVOICE_CLAIM"));
    assertThrows(IllegalStateException.class, () -> service.claim(confirmation(proofB)));
    var status = service.status(new ReceiptRequest(TOKEN));
    assertEquals(Status.CLAIMED_BY_ANOTHER_CUSTOMER, status.status()); assertNull(status.claimedAt());
    assertNull(eligible().claimProof()); assertEquals(A, owner.get().getCustomerId());
  }

  @Test void proofExpiresAndCannotOutliveReceipt() {
    access.setExpiresAt(NOW.plusSeconds(60)); var proof = eligible(); assertEquals(access.getExpiresAt(), proof.proofExpiresAt());
    now.set(NOW.plusSeconds(60));
    assertThrows(IllegalArgumentException.class, () -> service.claim(confirmation(proof.claimProof())));
    assertNull(owner.get());
  }

  @Test void revokedOrExpiredReceiptIsRevalidatedAtConfirmation() {
    String proof = eligible().claimProof();
    when(receipts.requireValidClaimReceipt(access.getId(), access.getTokenHash())).thenThrow(new IllegalArgumentException("Receipt unavailable"));
    assertThrows(IllegalArgumentException.class, () -> service.claim(confirmation(proof))); assertNull(owner.get());
  }

  @Test void invoiceBindingChangesAndMissingSellerTenantAreRejected() {
    String proof = eligible().claimProof(); invoice.setDistributionPartnerId("another-partner");
    assertThrows(IllegalArgumentException.class, () -> service.claim(confirmation(proof))); assertNull(owner.get());
    when(tenants.getById(SELLER)).thenThrow(new IllegalArgumentException("Missing tenant"));
    assertThrows(IllegalArgumentException.class, this::eligible);
  }

  @Test void wrongPortalOrMissingPermissionIsRejectedBeforeReceiptOrOwnershipLookups() {
    authenticate(A, SELLER, List.of("CUSTOMER_INVOICE_CLAIM"));
    assertThrows(ForbiddenException.class, this::eligible);
    authenticate(A, PORTAL, List.of("CUSTOMER_SELF_READ"));
    assertThrows(ForbiddenException.class, () -> service.status(new ReceiptRequest(TOKEN)));
    verifyNoInteractions(receipts, proofs, links);
  }

  @Test void disabledCustomerCannotUsePreviouslyIssuedProof() {
    String proof = eligible().claimProof();
    when(identities.requireAccount(A)).thenThrow(new ForbiddenException("Customer unavailable"));
    assertThrows(ForbiddenException.class, () -> service.claim(confirmation(proof))); assertNull(owner.get());
  }

  @Test void storageFailureDoesNotReportSuccessAndRetryIsSafe() {
    String proof = eligible().claimProof();
    when(links.insert(any(CustomerInvoiceLink.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"));
    assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> service.claim(confirmation(proof)));
    assertNull(owner.get());
  }

  @Test void twoDifferentCustomersCannotWinConcurrentClaims() throws Exception {
    String proofA = eligible().claimProof(); authenticate(B, PORTAL, List.of("CUSTOMER_INVOICE_CLAIM")); String proofB = eligible().claimProof();
    concurrentInserts = new CountDownLatch(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks = List.of(pool.submit(() -> concurrentClaim(A, proofA)), pool.submit(() -> concurrentClaim(B, proofB)));
      assertEquals(1, tasks.stream().filter(future -> { try { return future.get(10, TimeUnit.SECONDS); } catch (Exception ex) { throw new AssertionError(ex); } }).count());
      assertNotNull(owner.get());
    } finally { pool.shutdownNow(); }
  }

  @Test void concurrentRetriesBySameCustomerReturnSameCanonicalOwner() throws Exception {
    String proof = eligible().claimProof(); concurrentInserts = new CountDownLatch(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> concurrentClaim(A, proof)); var second = pool.submit(() -> concurrentClaim(A, proof));
      assertTrue(first.get(10, TimeUnit.SECONDS)); assertTrue(second.get(10, TimeUnit.SECONDS)); assertEquals(A, owner.get().getCustomerId());
    } finally { pool.shutdownNow(); }
  }

  private boolean concurrentClaim(String customer, String proof) {
    authenticate(customer, PORTAL, List.of("CUSTOMER_INVOICE_CLAIM"));
    try { service.claim(confirmation(proof)); return true; }
    catch (IllegalStateException ex) { return false; }
    finally { AuthenticationContext.unload(); }
  }
}
