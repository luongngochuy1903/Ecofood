package com.ecofood.app.common.client;

import com.ecofood.app.common.dto.EnterpriseCreateRequest;
import com.ecofood.app.common.dto.EnterpriseResponse;
import com.ecofood.app.common.service.EnterpriseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Cross-module delegate to EnterpriseService — holds the plain interface and
 * forwards every call, per this project's module-boundary pattern (see
 * CoreUserClient/UserService). Other modules (e.g. TenantServiceImpl) inject
 * this, never enterprise.service.EnterpriseServiceImpl directly.
 */
@Service
@RequiredArgsConstructor
public class CoreEnterpriseClient {

  private final EnterpriseService enterpriseService;

  public EnterpriseResponse createEnterprise(EnterpriseCreateRequest request) {
    return enterpriseService.createEnterprise(request);
  }
}
