package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.Status;
import com.ecofood.app.config.BadRequestException;
import java.util.*;

/** Structural transitions only; ComplaintAccessPolicy verifies the actor on each concrete case. */
public final class ComplaintStateMachine {
  private ComplaintStateMachine() {}
  private static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
      Status.SUBMITTED, Set.of(Status.IN_PROGRESS),
      Status.IN_PROGRESS, Set.of(Status.WAITING_REPORTER, Status.RESOLVED),
      Status.WAITING_REPORTER, Set.of(Status.IN_PROGRESS),
      Status.RESOLVED, Set.of(Status.CLOSED), Status.CLOSED, Set.of());
  public static Set<Status> allowedFrom(Status status) { return status == null ? Set.of() : TRANSITIONS.getOrDefault(status, Set.of()); }
  public static void requireTransition(Status from, Status to) {
    if (to == null || !allowedFrom(from).contains(to)) throw new BadRequestException("Invalid complaint state transition");
  }
}
