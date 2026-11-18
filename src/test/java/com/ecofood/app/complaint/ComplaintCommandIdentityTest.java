package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import org.junit.jupiter.api.Test;

class ComplaintCommandIdentityTest {
  final ComplaintCoreFixture f = new ComplaintCoreFixture();
  @Test void submissionKeysIncludeActorSourceAndRequestRatherThanInvoiceAlone() {
    var a = f.submit(Kind.INVOICE, "request-a"); var b = f.submit(Kind.INVOICE, "request-b");
    assertNotEquals(ComplaintCommandIdentity.submitKey(a), ComplaintCommandIdentity.submitKey(b));
    assertNotEquals(ComplaintCommandIdentity.submitKey(a), ComplaintCommandIdentity.submitKey(f.submit(Kind.INVOICE, "request-a", f.guest())));
    assertEquals(ComplaintCommandIdentity.submitKey(a), ComplaintCommandIdentity.submitKey(a));
  }
  @Test void sameKeyDifferentBusinessPayloadProducesDifferentFingerprint() {
    var a = f.submit(Kind.INVOICE, "request"); var b = f.content(a, "Different subject", a.description());
    assertEquals(ComplaintCommandIdentity.submitKey(a), ComplaintCommandIdentity.submitKey(b));
    assertNotEquals(ComplaintCommandIdentity.submitFingerprint(a), ComplaintCommandIdentity.submitFingerprint(b));
  }
  @Test void commandOperationAndExpectedVersionArePartOfPayloadNotDedupeKey() {
    var command = f.command(f.event(f.row(), Visibility.PUBLIC).result(), "flow", f.handler(), Visibility.PUBLIC);
    assertNotEquals(ComplaintCommandIdentity.workflowFingerprint(Operation.START_PROCESSING, command), ComplaintCommandIdentity.workflowFingerprint(Operation.RESUME_PROCESSING, command));
  }
  @Test void hashingIsLengthPrefixedAndDistinguishesNullFromEmpty() {
    assertNotEquals(ComplaintCommandIdentity.hash("ab", "c"), ComplaintCommandIdentity.hash("a", "bc"));
    assertNotEquals(ComplaintCommandIdentity.hash((Object) null), ComplaintCommandIdentity.hash(""));
  }
  @Test void onlyBusinessContentIsNormalizedForIdempotentPayloads() {
    var a = f.submit(Kind.PLATFORM, "request"); var b = f.content(a, " Subject ", " Description ");
    assertEquals(ComplaintCommandIdentity.submitFingerprint(a), ComplaintCommandIdentity.submitFingerprint(b));
  }
}
