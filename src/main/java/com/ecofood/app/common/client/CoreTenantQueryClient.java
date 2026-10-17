package com.ecofood.app.common.client;

import com.ecofood.app.common.service.TenantQueryService;
import com.ecofood.app.tenant.entity.TenantEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Cross-module entry point to TenantQueryService — e.g. userACL/auth call this, never tenant's package directly. */
@Service
@RequiredArgsConstructor
public class CoreTenantQueryClient {

  private final TenantQueryService tenantQueryService;

  public TenantEntity getById(String tenantId) {
    return tenantQueryService.getById(tenantId);
  }

  public TenantEntity getByRealm(String realm) {
    return tenantQueryService.getByRealm(realm);
  }
}
