package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static com.ecofood.app.complaint.ComplaintFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RoutingPolicyResolverTest {
  final ComplaintFixture f = new ComplaintFixture();
  @Test void invoiceRoutesOnlyToItsVerifiedSellerAndPartnerBindingForGuestOrCustomer() {
    f.policies.add(f.published(f.invoice()));
    var invoice = new VerifiedInvoice("invoice-1", T, "SHOP-A");
    var guest = f.resolver.invoice(invoice, f.guest(), "PRODUCT_ISSUE");
    assertEquals("review", guest.queueId()); assertEquals(new ProcessingScope(Boundary.TENANT, T), guest.processing());
    assertEquals(guest, f.resolver.invoice(invoice, f.customer("customer-1"), "PRODUCT_ISSUE"));
  }
  @Test void opaquePartnerIdIsNotTreatedAsATenantOrQueueAndUnmappedPartnersFailClosed() {
    f.policies.add(f.published(f.invoice()));
    for (String partner : List.of(T, "support", "OTHER-SHOP"))
      assertThrows(BadRequestException.class, () -> f.resolver.invoice(new VerifiedInvoice("invoice-1", T, partner), f.guest(), "PRODUCT_ISSUE"));
    assertThrows(BadRequestException.class, () -> f.resolver.invoice(new VerifiedInvoice("invoice-1", M, "SHOP-A"), f.guest(), "PRODUCT_ISSUE"));
  }
  @Test void platformComplaintRequiresCustomerAndAlwaysUsesPlatformSupport() {
    f.policies.add(f.published(f.platform()));
    var result = f.resolver.platform(f.customer("customer-1"), "LOGIN");
    assertEquals("support", result.queueId()); assertEquals(new ProcessingScope(Boundary.PLATFORM, P), result.processing());
    assertThrows(ForbiddenException.class, () -> f.resolver.platform(f.guest(), "LOGIN"));
    assertThrows(ForbiddenException.class, () -> f.resolver.platform(f.employee("handler", T), "LOGIN"));
  }
  @Test void internalInitialQueueIsTheNextConfiguredLevelOfAuthenticatedReporter() {
    f.policies.add(f.published(f.internal()));
    var decision = f.resolver.internal(f.employee("reporter", T), "TEAM");
    assertEquals("review", decision.queueId()); assertNotEquals("origin", decision.queueId());
    assertEquals("origin", decision.reporterOriginQueueId());
  }
  @Test void managerReporterStartsAtItsNextNodeRatherThanItsOwnReceivingQueue() {
    f.member("review", "manager-reporter", Authority.NONE, Set.of(QueueCapability.REPORT));
    var p = f.internal(); f.policies.add(f.published(f.draft(p.id(), T, p.kind(), "TEAM", p.queueIds(), p.edges(), Set.of("origin", "review"), List.of(), null)));
    assertEquals("final", f.resolver.internal(f.employee("manager-reporter", T), "TEAM").queueId());
  }
  @Test void permissionsOrLegacyAuthorityLevelAloneCannotInventAReportingHierarchy() {
    f.grant("system-level-user", T); f.policies.add(f.published(f.internal()));
    assertThrows(BadRequestException.class, () -> f.resolver.internal(f.employee("system-level-user", T), "TEAM"));
    // No role/RoleLevel field exists in resolver inputs. Changing queue membership is what changes the route.
    f.member("origin", "system-level-user", Authority.NONE, Set.of(QueueCapability.REPORT));
    assertEquals("review", f.resolver.internal(f.employee("system-level-user", T), "TEAM").queueId());
  }
  @Test void staleCreatePermissionAndWrongTenantSessionsCannotCreateInternalComplaints() {
    f.policies.add(f.published(f.internal())); f.permissions.put(T + ":reporter", Set.of());
    assertThrows(ForbiddenException.class, () -> f.resolver.internal(f.employee("reporter", T), "TEAM"));
    assertThrows(ForbiddenException.class, () -> f.resolver.internal(f.employee("reporter", M), "TEAM"));
    assertThrows(ForbiddenException.class, () -> f.resolver.internal(f.customer("customer-1"), "TEAM"));
  }
  @Test void escalationMovesExactlyOneEdgeAndDoesNotChangeComplaintStatusSourceOrVersion() {
    var policy = f.published(f.internal()); f.policies.add(policy);
    var complaint = f.at(policy, "review", Status.IN_PROGRESS, f.employee("reporter", T), "handler");
    var next = f.resolver.escalate(complaint, f.employee("handler", T), "Requires higher authority");
    assertEquals("final", next.queueId()); assertEquals(Status.IN_PROGRESS, complaint.status()); assertEquals(7, complaint.version()); assertEquals(T, complaint.sourceTenantId());
    var finalCase = f.at(policy, "final", Status.IN_PROGRESS, f.employee("reporter", T), null);
    assertEquals("support", f.resolver.escalate(finalCase, f.employee("authority", T), "Platform review required").queueId());
  }
  @Test void subTenantEscalatesThroughItsVerifiedMasterFinalAuthority() {
    f.tenants.put(T, new TenantInfo(T, TenantStructure.SUB_TENANT, M, false, true));
    var p = f.published(f.graph(f.internal(), Set.of("origin", "review", "final", "master-final", "support"),
        List.of(edge("origin", "review"), edge("review", "final"), edge("final", "master-final"), edge("master-final", "support"))));
    f.policies.add(p);
    assertEquals("master-final", f.resolver.escalate(f.at(p, "final", Status.IN_PROGRESS, f.employee("reporter", T), null), f.employee("authority", T), "Master review").queueId());
    var next = f.resolver.escalate(f.at(p, "master-final", Status.IN_PROGRESS, f.employee("reporter", T), null), f.employee("master-handler", M), "Platform review");
    assertEquals(Boundary.PLATFORM, next.processing().boundary());
  }
  @Test void missingPolicyOrDeactivatedQueueNeverFallsBackToPlatform() {
    assertThrows(BadRequestException.class, () -> f.resolver.internal(f.employee("reporter", T), "TEAM"));
    f.policies.add(f.published(f.internal()));
    var queue = f.queues.get("review"); f.queues.put("review", new ComplaintQueue(queue.id(), queue.name(), queue.scope(), queue.purpose(), false));
    assertThrows(BadRequestException.class, () -> f.resolver.internal(f.employee("reporter", T), "TEAM"));
  }
  @Test void twoActivePoliciesAreRejectedInsteadOfSelectingAnArbitraryVersion() {
    var p = f.published(f.internal()); f.policies.add(p); f.policies.add(p);
    assertThrows(BadRequestException.class, () -> f.resolver.internal(f.employee("reporter", T), "TEAM"));
  }
  @Test void draftsAndInactivePoliciesCannotRouteNewComplaints() {
    f.policies.add(f.internal());
    assertThrows(BadRequestException.class, () -> f.resolver.internal(f.employee("reporter", T), "TEAM"));
  }
  @Test void existingCasesPinAnOlderRetiredPublishedVersion() {
    var old = f.published(f.internal());
    f.policies.add(f.publisher.retire(old, f.employee("publisher", T)));
    var result = f.resolver.escalate(f.at(old, "review", Status.IN_PROGRESS, f.employee("reporter", T), null), f.employee("handler", T), "Needs final authority");
    assertEquals(old.version(), result.policyVersion()); assertEquals("final", result.queueId());
    assertThrows(BadRequestException.class, () -> f.resolver.internal(f.employee("reporter", T), "TEAM"));
  }
  @Test void terminalQueueDoesNotHaveAnImplicitPlatformSuccessor() {
    var p = f.published(f.invoice()); f.policies.add(p);
    assertThrows(BadRequestException.class, () -> f.resolver.escalate(f.at(p, "final", Status.IN_PROGRESS, f.customer("customer-1"), null), f.employee("authority", T), "No configured edge"));
  }
  @Test void closedCasesAndMissingEscalationReasonsAreRejected() {
    var p = f.published(f.internal()); f.policies.add(p);
    assertThrows(BadRequestException.class, () -> f.resolver.escalate(f.at(p, "review", Status.CLOSED, f.employee("reporter", T), null), f.employee("handler", T), "Cannot reopen by escalation"));
    assertThrows(BadRequestException.class, () -> f.resolver.escalate(f.at(p, "review", Status.IN_PROGRESS, f.employee("reporter", T), null), f.employee("handler", T), " "));
  }
  @Test void caseCannotJumpBetweenPartnerBranchesInsideTheSamePolicy() {
    f.addQueue("second-review", T, QueuePurpose.PROCESSING);
    f.member("second-review", "second-handler", Authority.NONE, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ESCALATE));
    var p = f.published(f.draft("invoice", T, Kind.INVOICE, "PRODUCT_ISSUE", Set.of("review", "second-review", "final"),
        List.of(edge("review", "final"), edge("second-review", "final")), Set.of(),
        List.of(new ComplaintRoutingPolicy.PartnerBinding("SHOP-A", "review"), new ComplaintRoutingPolicy.PartnerBinding("SHOP-B", "second-review")), null));
    f.policies.add(p);
    var wrongBranch = f.at(p, "second-review", Status.IN_PROGRESS, f.customer("owner"), null); // Invoice is SHOP-A.
    assertThrows(BadRequestException.class, () -> f.resolver.escalate(wrongBranch, f.employee("second-handler", T), "Must not cross partner branch"));
  }
}
