package com.ecofood.app.common.client;

import com.ecofood.app.common.dto.TenantOnboardRequest;
import com.ecofood.app.common.dto.TenantOnboardResponse;
import com.ecofood.app.common.service.TenantService;
import com.ecofood.app.tenant.entity.TenantEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Cross-module entry point to TenantService — any OTHER module must call this, never tenant's package directly. */
@Service
@RequiredArgsConstructor
public class CoreTenantClient {

  private final TenantService tenantService;

  public TenantOnboardResponse onboardTenant(TenantOnboardRequest request) {
    return tenantService.onboardTenant(request);
  }

  public TenantEntity getById(String tenantId) {
    return tenantService.getById(tenantId);
  }
}
