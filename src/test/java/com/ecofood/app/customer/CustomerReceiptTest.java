package com.ecofood.app.customer;

import static com.ecofood.app.customer.CustomerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.notification.*;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.UserAccessService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.web.server.ResponseStatusException;

class CustomerReceiptTest {
  AtomicCustomerMongo db; CustomerReceiptService service; CustomerAccess access; CustomerIdentity identity; CustomerClaimHandoff handoff;
  ThreadLocal<CustomerAccess.Actor> actors = ThreadLocal.withInitial(() -> actor(A));
  MockedStatic<AuthenticationContext> authentication;
  @BeforeEach void setup() throws Exception {
    db = new AtomicCustomerMongo(); access = mock(CustomerAccess.class); identity = mock(CustomerIdentity.class); var users = mock(UserAccessService.class);
    var mail = new NotificationSettings("sender@example.org", true, 3, ""); handoff = new CustomerClaimHandoff(db.mongo, mail);
    service = new CustomerReceiptService(db.mongo, access, users, settings(), new CustomerReceiptTokens(settings()), identity, mail, handoff);
    when(access.current()).thenAnswer(ignored -> actors.get());
    when(identity.config(TENANT)).thenReturn(new CustomerIdentity.LoginConfig(TENANT, "test", "test-public", true));
    when(users.resolveAccess(any(), any())).thenReturn(new UserAccessService.UserAccessClaims(List.of(), List.of(CustomerReceiptService.ISSUE, "TRANSACTION_SUBMIT"), List.of()));
    var seller = user(SELLER); seller.setUserCategory(UserCategory.builder().userCategoryCode("PARTNER").build()); db.insert(seller); db.insert(invoice());
    authentication = mockStatic(AuthenticationContext.class); authentication.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(SELLER)); authentication.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(TENANT));
    authentication.when(AuthenticationContext::getPermissions).thenReturn(List.of(CustomerReceiptService.ISSUE, "TRANSACTION_SUBMIT"));
  }
  @AfterEach void close() { authentication.close(); actors.remove(); }
  CustomerReceiptService.Issued issue() { return service.issue(INVOICE, new CustomerReceiptService.IssueRequest("f9cb5497-2ba8-4bf2-9457-494ef47e98d4")); }
  String token(CustomerReceiptService.Issued receipt) { return receipt.receiptUrl().substring(receipt.receiptUrl().indexOf("#token=") + 7); }
  @Test void guestCanViewMinimalInvoiceButIssueAndViewDoNotCreateEmailOrOwner() {
    var receipt = issue(); var guest = service.view(token(receipt)); assertEquals("INV-001", guest.invoiceNumber()); assertEquals(TENANT, guest.login().tenantId());
    assertTrue(db.rows(Notification.class).isEmpty()); assertTrue(db.rows(Submission.class).isEmpty());
    assertNull(db.converter.read(Invoice.class, db.raw(Invoice.class, new ObjectId(INVOICE))).getInvoiceHolder());
    assertFalse(db.raw(Invoice.class, new ObjectId(INVOICE)).toJson().contains(token(receipt))); assertTrue(receipt.qrPngBase64().startsWith("iVBOR"));
  }
  @Test void receiptRetryUsesSameTokenAndExpiryWithoutExtendingValidity() { var first = issue(); var again = issue(); assertEquals(first.receiptUrl(), again.receiptUrl()); assertEquals(first.expiresAt(), again.expiresAt()); }
  @Test void validClaimSetsOneCanonicalOwnerAndQueuesOnlyOwnerEmail() {
    var receipt = issue(); var result = service.claim(token(receipt)); var invoice = db.find(new Query(), CustomerInvoice.class).get(0);
    assertEquals(A, invoice.getInvoiceHolder().userId()); assertEquals(A, invoice.getInvoiceHolder().customerId()); assertEquals(A, invoice.getCustomerOwnership().userId());
    assertEquals("PENDING", result.emailStatus(), db.raw(Invoice.class, new ObjectId(INVOICE)).toJson()); assertEquals(1, db.rows(Notification.class).size());
    var notice = db.find(new Query(), Notification.class).get(0); assertEquals(user(A).getEmail(), notice.getSendTo()); assertTrue(notice.getContent().contains("/customer/invoices/")); assertFalse(notice.getContent().contains(token(receipt)));
    assertEquals("INVOICE_CLAIMED", db.find(new Query(), Submission.class).get(0).event());
    assertFalse(db.converter.read(CustomerInvoice.class, db.raw(Invoice.class, new ObjectId(INVOICE))).getCustomerClaimHandoff().pending());
  }
  @Test void invoiceIdNumberOrWrongSecretCannotClaim() {
    issue(); for (String invalid : List.of(INVOICE, "INV-001", "x".repeat(43))) assertThrows(ResponseStatusException.class, () -> service.claim(invalid));
    assertTrue(db.rows(Notification.class).isEmpty()); assertTrue(db.rows(Submission.class).isEmpty());
  }
  @Test void anotherCustomerCannotClaimOrReadLinkedInvoiceAndPublicLinkStopsWorking() {
    var token = token(issue()); service.claim(token); actors.set(actor(B));
    assertThrows(ResponseStatusException.class, () -> service.claim(token)); assertThrows(ResponseStatusException.class, () -> service.view(token));
    var history = new CustomerService(access, db.mongo); assertTrue(history.invoices(0, 20).content().isEmpty()); assertThrows(ResponseStatusException.class, () -> history.detail(INVOICE));
  }
  @Test void concurrentCustomersOnlyOneWinsAndOneNotificationIsCreated() throws Exception {
    var token = token(issue()); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
    try {
      java.util.function.Function<String, Callable<Boolean>> claim = id -> () -> { actors.set(actor(id)); start.await(); try { service.claim(token); return true; } catch (ResponseStatusException denied) { return false; } finally { actors.remove(); } };
      var a = pool.submit(claim.apply(A)); var b = pool.submit(claim.apply(B)); start.countDown(); assertNotEquals(a.get(), b.get());
      assertEquals(1, db.rows(Notification.class).size()); assertEquals(1, db.rows(Submission.class).size());
    } finally { pool.shutdownNow(); }
  }
  @Test void retryDoesNotResendOrChangeFinancialFieldsOrPaidSubmission() {
    var token = token(issue()); Document before = new Document(db.raw(Invoice.class, new ObjectId(INVOICE)));
    service.claim(token); var notice = db.find(new Query(), Notification.class).get(0); db.raw(Notification.class, notice.getId()).put("status", "SENT");
    assertEquals("SENT", service.claim(token).emailStatus()); assertEquals(1, db.rows(Notification.class).size()); assertEquals(1, db.rows(Submission.class).size());
    Document after = new Document(db.raw(Invoice.class, new ObjectId(INVOICE))); after.remove("invoiceHolder"); after.remove("customerOwnership"); after.remove("customerClaimHandoff");
    assertEquals(before, after); assertEquals("paid-event", after.getString("submissionId"));
    verify(db.mongo, never()).save(any());
  }
  @Test void failedHandoffRecoversWithoutUndoingOwnershipOrDuplicatingEvents() {
    var token = token(issue()); doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).doAnswer(a -> db.insert(a.getArgument(0))).when(db.mongo).insert(any(Submission.class));
    service.claim(token); assertTrue(db.rows(Notification.class).isEmpty());
    var invoice = db.find(new Query(), CustomerInvoice.class).get(0); assertEquals(A, invoice.getInvoiceHolder().userId()); assertTrue(invoice.getCustomerClaimHandoff().pending());
    service.claim(token); service.claim(token); assertEquals(1, db.rows(Notification.class).size()); assertEquals(1, db.rows(Submission.class).size());
  }
  @Test void expiryRevocationAndTenantMismatchRejectClaim() {
    var raw = token(issue()); service.revoke(INVOICE); assertThrows(ResponseStatusException.class, () -> service.claim(raw));
    var receipt = (Document) db.raw(Invoice.class, new ObjectId(INVOICE)).get("customerReceipt"); receipt.remove("revokedAt"); receipt.put("expiresAt", Date.from(Instant.now().minusSeconds(1)));
    assertThrows(ResponseStatusException.class, () -> service.view(raw)); assertThrows(ResponseStatusException.class, () -> service.claim(raw));
    receipt.put("expiresAt", Date.from(Instant.now().plusSeconds(100))); actors.set(new CustomerAccess.Actor(A, OTHER_TENANT, user(A)));
    assertThrows(ResponseStatusException.class, () -> service.claim(raw));
  }
  @Test void adminPermissionsCannotIssueReceiptForSomebodyElsesSubmission() {
    authentication.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(B));
    assertThrows(ResponseStatusException.class, this::issue);
  }
  @Test void reusingAnOldRequestAfterRotationsCannotReviveItsRevokedLink() throws Exception {
    var first = issue(); service.revoke(INVOICE);
    service.issue(INVOICE, new CustomerReceiptService.IssueRequest("731b7ba2-9e93-4e68-bb66-f89ec11382a4")); service.revoke(INVOICE);
    var replacement = issue(); assertNotEquals(first.receiptUrl(), replacement.receiptUrl());
    assertThrows(ResponseStatusException.class, () -> service.view(token(first))); assertNotNull(service.view(token(replacement)));
  }
}
