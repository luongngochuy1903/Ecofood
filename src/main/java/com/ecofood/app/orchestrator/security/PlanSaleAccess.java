package com.ecofood.app.orchestrator.security;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.app.proposition.service.*;
import com.ecofood.app.proposition.service.PlanAvailability;
import com.ecofood.app.proposition.service.PropositionAccess;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.client.CoreProductQueryClient;
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
  private final CoreProductProcessClient productProcesses;
  private final CoreProductQueryClient products;

  public record Scope(String tenantId, String actor, PropositionEntity proposition, Plan plan) {}

  /**
   * Visible Plans for the whole Proposition — backs the directory listing
   * and Sale Orchestrator CRUD (no longer per-Plan). Scoped to the caller's
   * own Tenant for a Tenant actor; an Enterprise actor instead sees every
   * Plan in the Proposition it owns (see currentForProposition).
   * `enterprise` lets the service layer branch its own Orchestrator lookups
   * (an Enterprise actor sees and can edit every Tenant's Orchestrators in
   * this Proposition, not only ones stored under its own tenantId).
   */
  public record PropositionScope(
      String tenantId,
      String actor,
      boolean enterprise,
      PropositionEntity proposition,
      java.util.List<Plan> plans) {}

  /**
   * Tenant-or-Enterprise gate for the whole Proposition at once — backs
   * both the Sale Orchestrator directory/CRUD (an Orchestrator can cover
   * many of the caller's own Plans at once) and, historically, the per-Plan
   * directory row join. A Tenant actor only ever sees its own assigned
   * Plans; an Enterprise actor owns the whole Proposition, so it sees every
   * Plan in it regardless of which Tenant each is assigned to.
   */
  public PropositionScope currentForProposition(String propositionId) {
    var actor = requireConfigRole();
    String subject = AuthenticationContext.getClaims().getSubject();
    if (subject == null || subject.isBlank())
      throw new ForbiddenException("Employee identity required");
    if (!ObjectId.isValid(propositionId)) throw missingProposition();
    var p =
        propositions
            .findById(new ObjectId(propositionId))
            .filter(v -> !Boolean.TRUE.equals(v.getDeleted()))
            .orElseThrow(PlanSaleAccess::missingProposition);
    access.read(actor, p);
    String tenantId = actor.tenant().id();
    var plans =
        p.getPlans().stream()
            .filter(
                v ->
                    !Boolean.TRUE.equals(v.getDeleted())
                        && (actor.enterprise()
                            || (v.getTenantIds() != null && v.getTenantIds().contains(tenantId))))
            .toList();
    return new PropositionScope(tenantId, subject, actor.enterprise(), p, plans);
  }

  private PropositionAccess.Actor requireConfigRole() {
    var actor = access.current();
    var roles = AuthenticationContext.getRoles();
    String prefix = actor.enterprise() ? "ENTERPRISE" : "TENANT";
    if (actor.platform()
        || roles == null
        || roles.stream()
            .noneMatch(r -> r.equals(prefix + "_ADMIN") || r.equals(prefix + "_PRODUCT_MANAGER")))
      throw new ForbiddenException("Tenant or Enterprise Sale Orchestrator management role required");
    return actor;
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
    requireApprovedProductVersion(scope);
  }

  /**
   * A Plan's own status being APPROVED only proves it WAS approved at some
   * point — it does not by itself prove the Product version it is still
   * pinned to (plan.getProductVersion()) is CURRENTLY Approved. The
   * demotion cascade (see ProductServiceImpl.demoteAnyOtherApprovedVersion
   * -> PropositionService.demoteApprovedPlans) is supposed to always keep
   * these in sync, but it writes the Product and the affected Propositions
   * in separate, non-atomic steps, so this re-checks the Product version
   * live rather than trusting the Plan's cached status alone.
   */
  private void requireApprovedProductVersion(Scope scope) {
    var productId = scope.proposition().getProductId();
    var version = scope.plan().getProductVersion();
    if (productId == null || version == null)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Plan's Product version is unavailable; cannot verify it is still Approved");
    products.getApprovedVersion(productId.toHexString(), version);
  }

  public String currency(Scope scope) {
    return costing(scope) == null || costing(scope).currency() == null
        ? scope.proposition().getDefinition().currency()
        : costing(scope).currency();
  }

  /** The Plan's own defined selling price — null if it hasn't set one yet (a factory-style item must then supply its own actualPrice). */
  public java.math.BigDecimal sellingPrice(Scope scope) {
    var costing = costing(scope);
    return costing == null ? null : costing.sellingPrice();
  }

  private com.ecofood.core.dto.ProductCostingAttributeResponse costing(Scope scope) {
    var content = productProcesses.get(scope.plan().getIdAsString());
    return content.costing() == null
            || content.costing().processElementAttribute() == null
            || content.costing().processElementAttribute().isEmpty()
        ? null
        : content.costing().processElementAttribute().get(0);
  }

  private static ResponseStatusException missing() {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "Plan is unavailable for this Tenant and Proposition");
  }

  private static ResponseStatusException missingProposition() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposition not found");
  }
}
