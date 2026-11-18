package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.ForbiddenException;
import java.time.Clock;
import java.util.Collections;
import lombok.RequiredArgsConstructor;

/** Returns a published immutable value; Task 2 owns atomic persistence/activation and uniqueness. */
@RequiredArgsConstructor
public class RoutingPolicyPublisher {
  private final ComplaintRoutingDirectory directory;
  private final RoutingPolicyValidator validator;
  private final Clock clock;
  public ComplaintRoutingPolicy publish(ComplaintRoutingPolicy draft, Actor actor) {
    validator.validate(draft);
    RoutingPolicyValidator.require(draft.publication() == Publication.DRAFT, "Only a draft can be published");
    requireManager(draft, actor);
    for (var existing : directory.policies()) {
      RoutingPolicyValidator.require(!(existing.id().equals(draft.id()) && existing.version() == draft.version() && existing.publication() != Publication.DRAFT), "Policy version already exists");
    }
    return new ComplaintRoutingPolicy(draft.id(), draft.version(), draft.ownerTenantId(), draft.kind(), draft.categoryCodes(), draft.queueIds(),
        draft.edges(), draft.reporterOriginQueueIds(), draft.partnerBindings(), draft.platformInitialQueueId(), Publication.PUBLISHED, false, clock.instant(), actor.userId());
  }
  public ComplaintRoutingPolicy activate(ComplaintRoutingPolicy published, Actor actor) {
    validator.validate(published); requireManager(published, actor);
    RoutingPolicyValidator.require(published.publication() == Publication.PUBLISHED, "Only a published policy can be activated");
    for (var existing : directory.policies()) {
      boolean self = existing.id().equals(published.id()) && existing.version() == published.version();
      RoutingPolicyValidator.require(!(!self && existing.active() && existing.publication() == Publication.PUBLISHED && existing.kind() == published.kind()
          && existing.ownerTenantId().equals(published.ownerTenantId()) && !Collections.disjoint(existing.categoryCodes(), published.categoryCodes())), "An active policy already covers these categories");
    }
    return lifecycle(published, Publication.PUBLISHED, true);
  }
  public ComplaintRoutingPolicy retire(ComplaintRoutingPolicy published, Actor actor) {
    requireManager(published, actor);
    RoutingPolicyValidator.require(published.publication() == Publication.PUBLISHED, "Only a published policy can be retired");
    return lifecycle(published, Publication.RETIRED, false);
  }
  private ComplaintRoutingPolicy lifecycle(ComplaintRoutingPolicy p, Publication state, boolean active) {
    return new ComplaintRoutingPolicy(p.id(), p.version(), p.ownerTenantId(), p.kind(), p.categoryCodes(), p.queueIds(), p.edges(),
        p.reporterOriginQueueIds(), p.partnerBindings(), p.platformInitialQueueId(), state, active, p.publishedAt(), p.publishedBy());
  }
  private void requireManager(ComplaintRoutingPolicy policy, Actor actor) {
    if (policy == null || actor == null || actor.type() != ActorType.EMPLOYEE || !policy.ownerTenantId().equals(actor.tenantId())
        || !actor.permissions().contains(Permission.COMPLAINT_ROUTING_MANAGE) || !directory.isActiveEmployee(actor.userId(), actor.tenantId())
        || !directory.hasPermission(actor.userId(), actor.tenantId(), Permission.COMPLAINT_ROUTING_MANAGE))
      throw new ForbiddenException("Complaint routing management access required");
  }
}
