package com.ecofood.app.enterprise.service;

import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.service.EnterpriseQueryService;
import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import com.ecofood.app.enterprise.repository.EnterpriseRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Read-only enterprise lookups for OTHER modules — userACL must never import
 * enterprise.repository.EnterpriseRepository directly, only
 * CoreEnterpriseQueryClient. Kept separate from EnterpriseServiceImpl (which
 * owns creation) — mirrors TenantQueryServiceImpl/TenantServiceImpl's split.
 */
@Service
@RequiredArgsConstructor
public class EnterpriseQueryServiceImpl implements EnterpriseQueryService {

  private final EnterpriseRepository enterpriseRepository;

  @Override
  public EnterpriseResponse getById(String enterpriseId) {
    EnterpriseEntity enterprise =
        enterpriseRepository
            .findById(new ObjectId(enterpriseId))
            .orElseThrow(() -> new IllegalArgumentException("Enterprise not found: " + enterpriseId));
    return EnterpriseResponse.builder()
        .enterpriseId(enterprise.getIdAsString())
        .enterpriseCompanyName(enterprise.getPartyName())
        .build();
  }
}
