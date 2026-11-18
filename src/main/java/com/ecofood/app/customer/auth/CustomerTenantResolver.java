package com.ecofood.app.customer.auth;

import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.enums.TenantTypeEnum;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Resolve identity metadata only from the server-selected, onboarded portal Tenant. */
@Service
@RequiredArgsConstructor
public class CustomerTenantResolver {
  private final CustomerAuthProperties properties;
  private final CoreTenantQueryClient tenants;

  public record Portal(String tenantId, String tenantName, String realm, String clientId, String issuer) {}

  public Portal resolve() {
    properties.requireConfigured();
    try {
      String id = new ObjectId(properties.getTenantId()).toHexString();
      var tenant = tenants.getById(id);
      if (tenant == null || !id.equals(tenant.getId()) || tenant.getRealm() == null
          || !tenant.getRealm().matches("[A-Za-z0-9_-]+") || "master".equals(tenant.getRealm())
          || tenant.getClientId() == null || tenant.getClientId().isBlank()
          || tenant.getTenantType() == TenantTypeEnum.SUB_TENANT) throw unavailable();
      // Do not enable public Customer enrollment in a realm/client shared by other business Tenants.
      var sharing = tenants.getAllByRealm(tenant.getRealm());
      if (sharing == null || sharing.size() != 1 || !id.equals(sharing.get(0).getId())) throw unavailable();
      String issuer = properties.getServerUrl().replaceAll("/+$", "") + "/realms/" + tenant.getRealm();
      return new Portal(id, tenant.getTenantName(), tenant.getRealm(), tenant.getClientId(), issuer);
    } catch (IllegalArgumentException ex) { throw unavailable(); }
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Customer portal tenant is not configured correctly");
  }
}
