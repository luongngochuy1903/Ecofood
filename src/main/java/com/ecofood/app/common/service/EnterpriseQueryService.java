package com.ecofood.app.common.service;

import com.ecofood.app.enterprise.entity.EnterpriseEntity;

/**
 * Plain same-module-facing interface, implemented by enterprise's
 * EnterpriseQueryServiceImpl. Returns the entity as-is (repository
 * passthrough) — mirrors TenantQueryService's exact pattern.
 */
public interface EnterpriseQueryService {

  EnterpriseEntity getById(String enterpriseId);
}
