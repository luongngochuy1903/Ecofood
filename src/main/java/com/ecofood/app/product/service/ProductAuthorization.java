package com.ecofood.app.product.service;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
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
