package com.ecofood.app.common.client;

import com.ecofood.app.common.service.UserRoleAssignmentService;
import com.ecofood.app.userACL.entity.PartnerTypeEnum;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/** Cross-module entry point to UserRoleAssignmentService — e.g. the tenant module calls this, never userACL directly. */
@Service
@RequiredArgsConstructor
public class CoreUserRoleAssignmentClient {

  private final UserRoleAssignmentService userRoleAssignmentService;

  public void assignRoleForOnboarding(ObjectId userId, ObjectId tenantId, PartnerTypeEnum partnerType) {
    userRoleAssignmentService.assignRoleForOnboarding(userId, tenantId, partnerType);
  }
}
