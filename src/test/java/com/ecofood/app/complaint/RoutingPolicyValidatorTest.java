package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static com.ecofood.app.complaint.ComplaintFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.BadRequestException;
import java.util.*;
import org.junit.jupiter.api.Test;

class RoutingPolicyValidatorTest {
  final ComplaintFixture f = new ComplaintFixture();
  void rejects(ComplaintRoutingPolicy policy) { assertThrows(BadRequestException.class, () -> f.validator.validate(policy)); }
  @Test void configuredStandaloneInvoicePlatformAndInternalPoliciesAreValid() {
    f.validator.validate(f.internal()); f.validator.validate(f.invoice()); f.validator.validate(f.platform());
  }
  @Test void subTenantMustPassItsOwnAndMasterFinalAuthoritiesBeforePlatform() {
    f.tenants.put(T, new TenantInfo(T, TenantStructure.SUB_TENANT, M, false, true));
    var policy = f.graph(f.internal(), Set.of("origin", "review", "final", "master-final", "support"),
        List.of(edge("origin", "review"), edge("review", "final"), edge("final", "master-final"), edge("master-final", "support")));
    f.validator.validate(policy); rejects(f.internal());
  }
  @Test void cyclesAreRejected() {
    f.addQueue("second", T, QueuePurpose.PROCESSING);
    f.member("second", "second-handler", Authority.NONE, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ESCALATE));
    rejects(f.graph(f.internal(), Set.of("origin", "review", "second", "final", "support"),
        List.of(edge("origin", "review"), edge("review", "second"), edge("second", "review"), edge("final", "support"))));
  }
  @Test void selfLoopsAreRejected() { rejects(f.graph(f.internal(), f.internal().queueIds(), List.of(edge("origin", "origin")))); }
  @Test void branchingAndDuplicateOutgoingEdgesAreRejected() {
    rejects(f.graph(f.internal(), f.internal().queueIds(), List.of(edge("origin", "review"), edge("review", "final"), edge("review", "support"))));
    rejects(f.graph(f.internal(), f.internal().queueIds(), List.of(edge("origin", "review"), edge("origin", "review"))));
  }
  @Test void unknownAndInactiveQueuesAreRejected() {
    f.queues.remove("review"); rejects(f.internal());
    f.queues.put("review", new ComplaintQueue("review", "Review", new ProcessingScope(Boundary.TENANT, T), QueuePurpose.PROCESSING, false)); rejects(f.internal());
  }
  @Test void membershipWithoutCurrentPermissionsOrEmploymentCannotReceive() {
    f.permissions.put(T + ":handler", Set.of(Permission.COMPLAINT_READ)); rejects(f.internal());
    f.grant("handler", T); f.employees.remove(T + ":handler"); rejects(f.internal());
  }
  @Test void processingPermissionWithoutQueueReadCapabilityIsNotEnough() {
    f.members.put("review", new ArrayList<>()); f.member("review", "other", Authority.NONE, Set.of(QueueCapability.PROCESS)); rejects(f.internal());
  }
  @Test void finalAuthorityRequiresCorrectAuthorityMembership() {
    f.members.put("final", new ArrayList<>()); f.member("final", "fake-final", Authority.NONE, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ESCALATE)); rejects(f.internal());
  }
  @Test void platformAuthorityCannotBeClaimedInsideATenantQueue() {
    f.members.put("final", new ArrayList<>()); f.member("final", "fake-final", Authority.PLATFORM_AUTHORITY, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ESCALATE)); rejects(f.internal());
  }
  @Test void intermediateQueueCannotSkipFinalAuthority() {
    rejects(f.graph(f.internal(), Set.of("origin", "review", "support"), List.of(edge("origin", "review"), edge("review", "support"))));
  }
  @Test void standaloneCannotChooseAnUnrelatedMasterTenant() {
    rejects(f.graph(f.internal(), Set.of("origin", "review", "final", "master-final", "support"),
        List.of(edge("origin", "review"), edge("review", "final"), edge("final", "master-final"), edge("master-final", "support"))));
  }
  @Test void subTenantWithMissingMasterFailsClosed() {
    f.tenants.put(T, new TenantInfo(T, TenantStructure.SUB_TENANT, "missing-master", false, true)); rejects(f.internal());
  }
  @Test void queueBoundaryCannotMasqueradeAsPlatform() {
    f.queues.put("support", new ComplaintQueue("support", "Support", new ProcessingScope(Boundary.PLATFORM, T), QueuePurpose.FINAL_AUTHORITY, true)); rejects(f.platform());
  }
  @Test void platformPolicyCannotUseTenantInbox() {
    var p = f.platform(); rejects(f.draft(p.id(), P, Kind.PLATFORM, "LOGIN", Set.of("review", "final"), List.of(edge("review", "final")), Set.of(), List.of(), "review"));
  }
  @Test void duplicateDistributionPartnerBindingsAreAmbiguous() {
    var p = f.invoice(); rejects(f.draft(p.id(), T, Kind.INVOICE, "PRODUCT_ISSUE", p.queueIds(), p.edges(), Set.of(),
        List.of(new ComplaintRoutingPolicy.PartnerBinding("SHOP-A", "review"), new ComplaintRoutingPolicy.PartnerBinding("SHOP-A", "final")), null));
  }
  @Test void invoiceInitialQueueCannotBePlatform() {
    rejects(f.draft("invoice", T, Kind.INVOICE, "PRODUCT_ISSUE", Set.of("support"), List.of(), Set.of(), List.of(new ComplaintRoutingPolicy.PartnerBinding("SHOP-A", "support")), null));
  }
  @Test void unreachableNodesAndNonFinalDeadEndsAreRejected() {
    rejects(f.graph(f.internal(), Set.of("origin", "review", "final", "support", "master-final"), f.internal().edges()));
    rejects(f.graph(f.internal(), Set.of("origin", "review"), List.of(edge("origin", "review"))));
  }
  @Test void sameReporterAtTwoOriginLevelsCannotPublishAnAmbiguousInitialRoute() {
    f.member("review", "reporter", Authority.NONE, Set.of(QueueCapability.REPORT));
    var p = f.internal(); rejects(f.draft(p.id(), T, Kind.INTERNAL, "TEAM", p.queueIds(), p.edges(), Set.of("origin", "review"), List.of(), null));
  }
  @Test void missingCategoryAndInvalidPublicationStateAreRejected() {
    var p = f.internal(); rejects(f.draft(p.id(), T, Kind.INTERNAL, "UNKNOWN", p.queueIds(), p.edges(), p.reporterOriginQueueIds(), List.of(), null));
    rejects(new ComplaintRoutingPolicy(p.id(), 1, T, p.kind(), p.categoryCodes(), p.queueIds(), p.edges(), p.reporterOriginQueueIds(), List.of(), null, Publication.DRAFT, true, null, null));
  }
  @Test void queueWithoutAnyEligibleEscalatorCannotPublishAnOutgoingEdge() {
    f.members.put("review", new ArrayList<>()); f.member("review", "handler", Authority.NONE, Set.of(QueueCapability.READ, QueueCapability.PROCESS)); rejects(f.internal());
  }
  @Test void twoFinalAuthoritiesInsideOneTenantAreRejected() {
    f.addQueue("other-final", T, QueuePurpose.FINAL_AUTHORITY);
    f.member("other-final", "other-authority", Authority.TENANT_AUTHORITY, Set.of(QueueCapability.READ, QueueCapability.PROCESS));
    rejects(f.graph(f.internal(), Set.of("origin", "review", "final", "other-final", "support"), f.internal().edges()));
  }
  @Test void platformSupportMayHaveItsOwnConfiguredProcessingLevelsAfterAuthorizedHandoff() {
    f.queues.put("support", new ComplaintQueue("support", "Platform Support", new ProcessingScope(Boundary.PLATFORM, P), QueuePurpose.PROCESSING, true));
    f.members.put("support", new ArrayList<>());
    f.member("support", "support-worker", Authority.NONE, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ESCALATE));
    f.addQueue("platform-final", P, QueuePurpose.FINAL_AUTHORITY);
    f.member("platform-final", "platform-authority", Authority.PLATFORM_AUTHORITY, Set.of(QueueCapability.READ, QueueCapability.PROCESS));
    var p = f.internal();
    f.validator.validate(f.graph(p, Set.of("origin", "review", "final", "support", "platform-final"),
        List.of(edge("origin", "review"), edge("review", "final"), edge("final", "support"), edge("support", "platform-final"))));
  }
}
