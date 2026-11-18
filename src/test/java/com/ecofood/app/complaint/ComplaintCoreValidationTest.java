package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static com.ecofood.app.complaint.ComplaintCoreFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComplaintCoreValidationTest {
  final ComplaintCoreFixture f = new ComplaintCoreFixture(); final ComplaintValidation validation = new ComplaintValidation(f.invoices);
  @Test void allThreeKindsAndGuestInvoiceValidateWithoutAssumingPortalIsSeller() {
    for (Kind kind : Kind.values()) validation.submission(f.submit(kind, "request-1"));
    validation.submission(f.submit(Kind.INVOICE, "guest", f.guest())); assertNotEquals(PORTAL, SELLER);
  }
  @Test void contentAndRoutingAreRequiredAndBounded() {
    var c = f.submit(Kind.INVOICE, "request");
    assertThrows(BadRequestException.class, () -> validation.submission(f.content(c, " ", "Description")));
    assertThrows(BadRequestException.class, () -> validation.submission(f.content(c, "x".repeat(201), "Description")));
    assertThrows(BadRequestException.class, () -> validation.submission(f.content(c, "Subject", "x".repeat(10001))));
    var bad = new SubmitCommand(c.requestId(), null, c.kind(), c.categoryCode(), c.subject(), c.description(), c.actor(), c.invoice(), c.invoiceItemReferences(), null, null);
    assertThrows(BadRequestException.class, () -> validation.submission(bad));
  }
  @Test void wrongInvoiceTenantPartnerAndItemsAreRejectedWithoutMutatingInvoice() {
    var c = f.submit(Kind.INVOICE, "request");
    for (var ref : List.of(new VerifiedInvoice(INVOICE, PORTAL, "SHOP-OPAQUE"), new VerifiedInvoice(INVOICE, SELLER, SELLER))) {
      var bad = new SubmitCommand(c.requestId(), null, c.kind(), c.categoryCode(), c.subject(), c.description(), c.actor(), ref, List.of(), c.routing(), null);
      assertThrows(BadRequestException.class, () -> validation.submission(bad));
    }
    for (var items : List.of(List.of(new InvoiceItemReference(1, "P1")), List.of(new InvoiceItemReference(0, "P2")), List.of(new InvoiceItemReference(0, "P1"), new InvoiceItemReference(0, "P1")))) {
      var bad = new SubmitCommand(c.requestId(), null, c.kind(), c.categoryCode(), c.subject(), c.description(), c.actor(), c.invoice(), items, c.routing(), null);
      assertThrows(BadRequestException.class, () -> validation.submission(bad));
    }
    assertEquals("INV-ORIGINAL", f.invoice.getInvoiceNumber()); assertEquals("SHOP-OPAQUE", f.invoice.getDistributionPartnerId());
  }
  @Test void kindsCannotSmuggleUnrelatedInvoiceReferencesOrReporterTypes() {
    var invoice = f.submit(Kind.INVOICE, "request");
    for (Kind kind : List.of(Kind.PLATFORM, Kind.INTERNAL)) {
      var base = f.submit(kind, "request");
      var bad = new SubmitCommand(base.requestId(), null, kind, base.categoryCode(), base.subject(), base.description(), base.actor(), invoice.invoice(), List.of(), base.routing(), null);
      assertThrows(BadRequestException.class, () -> validation.submission(bad));
    }
    assertThrows(BadRequestException.class, () -> validation.submission(f.submit(Kind.INTERNAL, "request", f.customer())));
    assertThrows(BadRequestException.class, () -> validation.submission(f.submit(Kind.PLATFORM, "request", f.guest())));
  }
  @Test void requiredGuestSourceAndAuthenticatedIdentifiersAreValidated() {
    var c = f.submit(Kind.INVOICE, "guest", f.guest());
    var bad = new SubmitCommand(c.requestId(), null, c.kind(), c.categoryCode(), c.subject(), c.description(), c.actor(), c.invoice(), List.of(), c.routing(), null);
    assertThrows(BadRequestException.class, () -> validation.submission(bad));
    assertThrows(BadRequestException.class, () -> validation.submission(f.submit(Kind.PLATFORM, "request", new Actor(ActorType.CUSTOMER, null, PORTAL, Set.of()))));
  }
  @Test void workflowRequiresReasonVisibilityVersionAndCanonicalCaseId() {
    var result = f.event(f.row(), Visibility.PUBLIC).result();
    validation.workflow(f.command(result, "start", f.handler(), Visibility.INTERNAL));
    var bad = new WorkflowCommand(CASE, -1, "start", null, f.handler(), null, "Reason", Visibility.PUBLIC);
    assertThrows(BadRequestException.class, () -> validation.workflow(bad));
    assertThrows(BadRequestException.class, () -> validation.workflow(new WorkflowCommand(CASE, 0, "start", null, f.handler(), null, " ", Visibility.PUBLIC)));
  }
  @Test void receivingQueueRequiresCaseLevelDataScopeAndMissingAuthorizationFailsClosed() {
    var row = f.row(); f.access.requireReceivingQueue(row.asCase()); f.denied.add(HANDLER);
    assertThrows(ForbiddenException.class, () -> f.access.requireReceivingQueue(row.asCase()));
    var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    assertThrows(IllegalStateException.class, () -> new ComplaintAuthorization(factory.getBeanProvider(ComplaintAccessPolicy.class)).requirePolicy());
  }
}
