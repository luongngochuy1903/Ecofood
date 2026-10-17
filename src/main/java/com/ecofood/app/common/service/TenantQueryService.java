package com.ecofood.app.common.service;

import com.ecofood.app.tenant.entity.TenantEntity;

/**
 * Plain same-module-facing interface, implemented by tenant's
 * TenantQueryServiceImpl. Returns the entity as-is (repository passthrough).
 */
public interface TenantQueryService {

  TenantEntity getById(String tenantId);

  TenantEntity getByRealm(String realm);
}
