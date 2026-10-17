package com.ecofood.app.common.service;

import com.ecofood.app.common.dto.TenantOnboardRequest;
import com.ecofood.app.common.dto.TenantOnboardResponse;
import com.ecofood.app.tenant.entity.TenantEntity;

/**
 * Plain same-module-facing interface, implemented by tenant's TenantServiceImpl.
 * getById returns the entity as-is — repository passthrough, not DTO-converted.
 */
public interface TenantService {

  TenantOnboardResponse onboardTenant(TenantOnboardRequest request);

  TenantEntity getById(String tenantId);
}
