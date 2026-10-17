package com.ecofood.app.tenant.service;

import com.ecofood.app.common.service.TenantQueryService;
import com.ecofood.app.tenant.entity.TenantEntity;
import com.ecofood.app.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Read-only tenant lookups for OTHER modules — userACL/auth must never
 * import tenant.repository.TenantRepository directly, only
 * CoreTenantQueryClient. Kept separate from TenantServiceImpl (which owns
 * onboarding orchestration and already depends on CoreUserClient)
 * specifically so userACL's UserServiceImpl can depend on tenant lookups
 * without creating a circular bean dependency.
 */
@Service
@RequiredArgsConstructor
public class TenantQueryServiceImpl implements TenantQueryService {

  private final TenantRepository tenantRepository;

  @Override
  public TenantEntity getById(String tenantId) {
    return tenantRepository
        .findById(new ObjectId(tenantId))
        .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
  }

  @Override
  public TenantEntity getByRealm(String realm) {
    return tenantRepository
        .findByRealm(realm)
        .orElseThrow(() -> new IllegalArgumentException("No tenant found for realm=" + realm));
  }
}
