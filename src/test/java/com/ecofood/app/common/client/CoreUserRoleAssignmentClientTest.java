package com.ecofood.app.common.client;

import static org.mockito.Mockito.verify;

import com.ecofood.app.common.service.UserRoleAssignmentService;
import com.ecofood.app.userACL.entity.PartnerTypeEnum;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreUserRoleAssignmentClientTest {

  @Mock private UserRoleAssignmentService userRoleAssignmentService;

  @Test
  void assignRoleForOnboardingDelegatesToUserRoleAssignmentService() {
    CoreUserRoleAssignmentClient client = new CoreUserRoleAssignmentClient(userRoleAssignmentService);
    ObjectId userId = new ObjectId();
    ObjectId tenantId = new ObjectId();

    client.assignRoleForOnboarding(userId, tenantId, PartnerTypeEnum.ENTERPRISE);

    verify(userRoleAssignmentService).assignRoleForOnboarding(userId, tenantId, PartnerTypeEnum.ENTERPRISE);
  }
}
