package com.ecofood.app.common.service;

import com.ecofood.app.common.dto.EnterpriseCreateRequest;
import com.ecofood.app.common.dto.EnterpriseResponse;

/**
 * Plain, same-module-facing contract for the enterprise module — implemented
 * only by EnterpriseServiceImpl. Cross-module callers (e.g. TenantServiceImpl)
 * go through CoreEnterpriseClient instead, per this project's module-boundary
 * pattern (see CoreUserClient/UserService).
 */
public interface EnterpriseService {

  EnterpriseResponse createEnterprise(EnterpriseCreateRequest request);
}
