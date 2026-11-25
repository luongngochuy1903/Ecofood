package com.ecofood.app.product.service;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.PartyRelationshipResponse;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
/** Server-derived enterprise ownership; never trust an enterpriseId from a request body. */
@Component @RequiredArgsConstructor
public class ProductAuthorization {
  private final ACLService aclService;
  private final CoreTenantQueryClient tenantQueryClient;

  /**
   * Read-only Product Library visibility — wider than {@link #requireEnterprise()}:
   * ECO_ADMIN/ECO_PRODUCT_MANAGER (SYSTEM role group) get unrestricted read across
   * every Enterprise's Products; TENANT_PRODUCT_MANAGER (TENANT role group) gets a
   * scoped read — their own tenant plus related tenant ids, same resolution
   * PropositionAccess uses for its own platform/scoped actors. An Enterprise
   * actor's visibility is unchanged: just its own single Enterprise, via
   * {@link #requireEnterprise()}. Never used to gate writes —
   * create/update/submit/approve/reject stay Enterprise-only.
   *
   * <p>No MASTER_TENANT branch: that role group is never actually granted to
   * any user today (no onboarding path or seed data produces it), so there's
   * no real MASTER_PRODUCT_MANAGER actor to support.
   */
  public record ReadActor(Set<String> enterpriseScope, boolean platform) {}

  public ReadActor currentRead() {
    if (AuthenticationContext.getToken() == null)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    if (aclService.hasSystemLevelRole()) {
      requireRole("ECO_ADMIN", "ECO_PRODUCT_MANAGER");
      return new ReadActor(Set.of(), true);
    }
    if (aclService.hasTenantLevelRole()) {
      requireRole("TENANT_PRODUCT_MANAGER");
      return new ReadActor(scopeOf(requireTenantId()), false);
    }
    return new ReadActor(Set.of(requireEnterprise().toHexString()), false);
  }

  public boolean canRead(ReadActor actor, ObjectId productEnterpriseId) {
    return productEnterpriseId != null
        && (actor.platform() || actor.enterpriseScope().contains(productEnterpriseId.toHexString()));
  }

  private void requireRole(String... allowed) {
    var roles = AuthenticationContext.getRoles();
    if (roles == null || roles.stream().noneMatch(role -> List.of(allowed).contains(role)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Product Library role required");
  }

  private ObjectId requireTenantId() {
    try {
      return AuthenticationContext.getTenantId();
    } catch (RuntimeException ex) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant claim is invalid");
    }
  }

  private Set<String> scopeOf(ObjectId tenantId) {
    var tenant = tenantQueryClient.propositionReference(tenantId.toHexString());
    var scope =
        new HashSet<>(tenant.scopedTenantIds() == null ? List.<String>of() : tenant.scopedTenantIds());
    scope.add(tenantId.toHexString());
    if (tenant.relationships() != null)
      tenant.relationships().stream()
          .filter(r -> RelationshipTypeEnum.MASTER_TENANT.getCode().equals(r.getRelationshipTypeCode()))
          .map(PartyRelationshipResponse::getToPartyId)
          .filter(Objects::nonNull)
          .forEach(scope::add);
    return Set.copyOf(scope);
  }

  public ObjectId requireEnterprise() {
    if (AuthenticationContext.getToken() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    if (!aclService.hasEnterpriseLevelRole()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Enterprise role required");
    }
    var roles = AuthenticationContext.getRoles();
    if (roles == null || roles.stream().noneMatch(role -> "ENTERPRISE_ADMIN".equals(role) || "ENTERPRISE_PRODUCT_MANAGER".equals(role))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Enterprise Product Library role required");
    }
    String tenantId;
    try { tenantId = AuthenticationContext.getTenantId().toHexString(); }
    catch (RuntimeException ex) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant claim is invalid");
    }
    TenantResponse tenant = tenantQueryClient.getById(tenantId);
    if (tenant.getEnterpriseId() == null || !ObjectId.isValid(tenant.getEnterpriseId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant has no Enterprise association");
    }
    return new ObjectId(tenant.getEnterpriseId());
  }
  public void requireOwned(ObjectId owner, ObjectId actualEnterprise) {
    if (owner == null || !owner.equals(actualEnterprise)) {
      // Do not reveal product existence across enterprises.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
    }
  }
}
