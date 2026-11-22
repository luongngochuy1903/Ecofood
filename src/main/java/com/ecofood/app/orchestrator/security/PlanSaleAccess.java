package com.ecofood.app.orchestrator.security;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.app.proposition.service.*;
import com.ecofood.app.proposition.service.PlanAvailability;
import com.ecofood.app.proposition.service.PropositionAccess;
import com.ecofood.core.security.AuthenticationContext;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class PlanSaleAccess {
  private final PropositionAccess access;
  private final PropositionRepository propositions;

  public record Scope(String tenantId, String actor, PropositionEntity proposition, Plan plan) {}

  public Scope current(String propositionId, String planId) {
    var actor = access.current();
    var roles = AuthenticationContext.getRoles();
    if (actor.enterprise()
        || actor.platform()
        || roles == null
        || roles.stream()
            .noneMatch(r -> r.equals("TENANT_ADMIN") || r.equals("TENANT_PRODUCT_MANAGER")))
      throw new ForbiddenException("Tenant Sale Orchestrator management role required");
    String subject = AuthenticationContext.getClaims().getSubject();
    if (subject == null || subject.isBlank())
      throw new ForbiddenException("Employee identity required");
    var scope = require(actor.tenant().id(), propositionId, planId);
    access.read(actor, scope.proposition());
    return new Scope(scope.tenantId(), subject, scope.proposition(), scope.plan());
  }

  /**
   * Used on every runtime request too: a previously activated Plan may expire or lose distribution.
   */
  public Scope require(String tenantId, String propositionId, String planId) {
    if (tenantId == null || !ObjectId.isValid(propositionId) || !ObjectId.isValid(planId))
      throw missing();
    var p =
        propositions
            .findById(new ObjectId(propositionId))
            .filter(v -> !Boolean.TRUE.equals(v.getDeleted()))
            .orElseThrow(PlanSaleAccess::missing);
    if (p.getDefinition() == null
        || p.getDefinition().distributionPartnerIds() == null
        || !p.getDefinition().distributionPartnerIds().contains(tenantId)) throw missing();
    String canonicalPlanId = new ObjectId(planId).toHexString();
    var plan =
        p.getPlans().stream()
            .filter(v -> canonicalPlanId.equals(v.getIdAsString()))
            .filter(
                v ->
                    !Boolean.TRUE.equals(v.getDeleted())
                        && v.getTenantIds() != null
                        && v.getTenantIds().contains(tenantId))
            .findFirst()
            .orElseThrow(PlanSaleAccess::missing);
    return new Scope(tenantId, null, p, plan);
  }

  public void active(Scope scope) {
    if (!PlanAvailability.active(scope.proposition(), scope.plan(), Instant.now()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Proposition and Plan must be approved and effective before activation or sales");
  }

  public String currency(Scope scope) {
    return scope.plan().getMarketConfiguration() == null
        ? scope.proposition().getDefinition().currency()
        : scope.plan().getMarketConfiguration().currency();
  }

  private static ResponseStatusException missing() {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "Plan is unavailable for this Tenant and Proposition");
  }
}
