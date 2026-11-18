package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static com.ecofood.app.complaint.ComplaintFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComplaintAccessPolicyTest {
  final ComplaintFixture f = new ComplaintFixture();
  @Test void structuralStateMachineAcceptsExactlyTheDocumentedTransitions() {
    Set<String> allowed = Set.of("SUBMITTED:IN_PROGRESS", "IN_PROGRESS:WAITING_REPORTER", "WAITING_REPORTER:IN_PROGRESS", "IN_PROGRESS:RESOLVED", "RESOLVED:CLOSED");
    for (Status from : Status.values()) for (Status to : Status.values()) {
      if (allowed.contains(from + ":" + to)) assertDoesNotThrow(() -> ComplaintStateMachine.requireTransition(from, to));
      else assertThrows(BadRequestException.class, () -> ComplaintStateMachine.requireTransition(from, to));
    }
    assertThrows(BadRequestException.class, () -> ComplaintStateMachine.requireTransition(null, Status.IN_PROGRESS));
  }
  @Test void customerReporterCanReadOwnCaseButCannotStartOrResolveIt() {
    var reporter = f.customer("owner"); var c = f.at(f.invoice(), "review", Status.SUBMITTED, reporter, null);
    f.access.requireRead(c, reporter, null);
    assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.customer("other"), null));
    assertThrows(ForbiddenException.class, () -> f.access.requireTransition(c, reporter, null, Status.IN_PROGRESS));
    assertThrows(ForbiddenException.class, () -> f.access.requireTransition(f.at(f.invoice(), "review", Status.IN_PROGRESS, reporter, null), reporter, null, Status.RESOLVED));
  }
  @Test void reporterReplyAndClosureDoNotGrantProcessingPermissions() {
    var reporter = f.customer("owner");
    f.access.requireTransition(f.at(f.invoice(), "review", Status.WAITING_REPORTER, reporter, null), reporter, null, Status.IN_PROGRESS);
    f.access.requireTransition(f.at(f.invoice(), "review", Status.RESOLVED, reporter, null), reporter, null, Status.CLOSED);
  }
  @Test void guestAccessIsCaseBoundAndExpiryCheckedAndClaimingInvoiceDoesNotBecomeAuthorship() {
    var c = f.at(f.invoice(), "review", Status.WAITING_REPORTER, f.guest(), null);
    var proof = new VerifiedGuestAccess(c.id(), NOW.plusSeconds(60)); f.access.requireRead(c, f.guest(), proof);
    f.access.requireTransition(c, f.guest(), proof, Status.IN_PROGRESS);
    assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.guest(), new VerifiedGuestAccess("other-case", NOW.plusSeconds(60))));
    assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.guest(), new VerifiedGuestAccess(c.id(), NOW)));
    assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.customer("invoice-claimant"), null));
  }
  @Test void assignedEmployeeWithCurrentQueuePermissionAndScopeCanProcess() {
    var c = f.at(f.invoice(), "review", Status.SUBMITTED, f.customer("owner"), "handler");
    f.access.requireRead(c, f.employee("handler", T), null); f.access.requireTransition(c, f.employee("handler", T), null, Status.IN_PROGRESS);
    f.access.requireTransition(f.at(f.invoice(), "review", Status.IN_PROGRESS, f.customer("owner"), "handler"), f.employee("handler", T), null, Status.WAITING_REPORTER);
    f.access.requireTransition(f.at(f.invoice(), "review", Status.IN_PROGRESS, f.customer("owner"), "handler"), f.employee("handler", T), null, Status.RESOLVED);
  }
  @Test void anotherQueueMemberMayReadButCannotProcessSomebodyElsesAssignment() {
    f.member("review", "colleague", Authority.NONE, Set.of(QueueCapability.READ, QueueCapability.PROCESS));
    var c = f.at(f.invoice(), "review", Status.SUBMITTED, f.customer("owner"), "handler");
    f.access.requireRead(c, f.employee("colleague", T), null);
    assertThrows(ForbiddenException.class, () -> f.access.requireTransition(c, f.employee("colleague", T), null, Status.IN_PROGRESS));
  }
  @Test void permissionDoesNotReplaceDataScopeQueueOrTenantMembership() {
    var c = f.at(f.invoice(), "review", Status.SUBMITTED, f.customer("owner"), null);
    f.deniedScopes.add("handler"); assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.employee("handler", T), null));
    f.deniedScopes.clear(); assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.employee("handler", M), null));
    f.grant("powerful-user", T); assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.employee("powerful-user", T), null));
  }
  @Test void staleJwtPermissionsAreRecheckedAndRevokedEmployeesLoseAccess() {
    var c = f.at(f.invoice(), "review", Status.SUBMITTED, f.customer("owner"), null);
    f.permissions.put(T + ":handler", Set.of(Permission.COMPLAINT_READ));
    assertThrows(ForbiddenException.class, () -> f.access.requireTransition(c, f.employee("handler", T), null, Status.IN_PROGRESS));
    f.employees.remove(T + ":handler"); assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.employee("handler", T), null));
  }
  @Test void onlyReporterOrConfiguredFinalAuthorityCanCloseResolvedCase() {
    var reporter = f.customer("owner");
    assertThrows(ForbiddenException.class, () -> f.access.requireTransition(f.at(f.invoice(), "review", Status.RESOLVED, reporter, null), f.employee("handler", T), null, Status.CLOSED));
    f.access.requireTransition(f.at(f.invoice(), "final", Status.RESOLVED, reporter, null), f.employee("authority", T), null, Status.CLOSED);
    f.access.requireTransition(f.at(f.platform(), "support", Status.RESOLVED, reporter, null), f.employee("platform-handler", P), null, Status.CLOSED);
  }
  @Test void assignRequiresAnEligibleTargetInTheSameCurrentQueueAndResourceScope() {
    var c = f.at(f.invoice(), "review", Status.IN_PROGRESS, f.customer("owner"), "handler");
    f.access.requireAssign(c, f.employee("handler", T), "handler");
    assertThrows(ForbiddenException.class, () -> f.access.requireAssign(c, f.employee("handler", T), "platform-handler"));
    f.deniedScopes.add("handler"); assertThrows(ForbiddenException.class, () -> f.access.requireAssign(c, f.employee("handler", T), "handler"));
  }
  @Test void assignmentsOnResolvedAndClosedCasesAreRejected() {
    for (var state : List.of(Status.RESOLVED, Status.CLOSED))
      assertThrows(ForbiddenException.class, () -> f.access.requireAssign(f.at(f.invoice(), "review", state, f.customer("owner"), null), f.employee("handler", T), "handler"));
  }
  @Test void authorityDoesNotGrantGlobalAccessToOtherQueuesOrTenants() {
    var c = f.at(f.invoice(), "review", Status.IN_PROGRESS, f.customer("owner"), null);
    assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.employee("authority", T), null));
    assertThrows(ForbiddenException.class, () -> f.access.requireRead(c, f.employee("platform-handler", P), null));
  }
  @Test void processingScopeCannotOverrideDeniedReadScope() {
    var c = f.at(f.invoice(), "review", Status.SUBMITTED, f.customer("owner"), null);
    f.deniedOperations.add("handler:" + Permission.COMPLAINT_READ);
    assertThrows(ForbiddenException.class, () -> f.access.requireTransition(c, f.employee("handler", T), null, Status.IN_PROGRESS));
  }
  @Test void crossingFinalAuthorityRequiresAuthorityMembershipNotMerelyAnEscalatePermission() {
    var c = f.at(f.internal(), "final", Status.IN_PROGRESS, f.employee("reporter", T), null);
    f.member("final", "ordinary-member", Authority.NONE, Set.of(QueueCapability.READ, QueueCapability.PROCESS, QueueCapability.ESCALATE));
    assertThrows(ForbiddenException.class, () -> f.access.requireEscalate(c, f.employee("ordinary-member", T)));
    f.access.requireEscalate(c, f.employee("authority", T));
  }
}
