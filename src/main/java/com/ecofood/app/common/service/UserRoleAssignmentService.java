package com.ecofood.app.common.service;

import com.ecofood.app.userACL.entity.PartnerTypeEnum;
import org.bson.types.ObjectId;

/** Plain same-module-facing interface, implemented by userACL's UserRoleAssignmentServiceImpl. */
public interface UserRoleAssignmentService {

  void assignRoleForOnboarding(ObjectId userId, ObjectId tenantId, PartnerTypeEnum partnerType);
}
