package com.ecofood.app.common.client;

import com.ecofood.app.common.service.EnterpriseQueryService;
import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Cross-module entry point to EnterpriseQueryService — e.g. userACL calls this, never enterprise's package directly. */
@Service
@RequiredArgsConstructor
public class CoreEnterpriseQueryClient {

  private final EnterpriseQueryService enterpriseQueryService;

  public EnterpriseEntity getById(String enterpriseId) {
    return enterpriseQueryService.getById(enterpriseId);
  }
}
