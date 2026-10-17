package com.ecofood.app.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ecofood.app.common.service.UserAccessService;
import com.ecofood.app.common.service.UserAccessService.UserAccessClaims;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreUserAccessClientTest {

  @Mock private UserAccessService userAccessService;

  @Test
  void resolveAccessDelegatesToUserAccessService() {
    CoreUserAccessClient client = new CoreUserAccessClient(userAccessService);
    ObjectId userId = new ObjectId();
    ObjectId tenantId = new ObjectId();
    UserAccessClaims expected = new UserAccessClaims(List.of("TENANT_ADMIN"), List.of(), List.of());
    when(userAccessService.resolveAccess(userId, tenantId)).thenReturn(expected);

    assertThat(client.resolveAccess(userId, tenantId)).isSameAs(expected);
  }
}
