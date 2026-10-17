package com.ecofood.app.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ecofood.app.common.dto.EnterpriseCreateRequest;
import com.ecofood.app.common.dto.EnterpriseResponse;
import com.ecofood.app.common.service.EnterpriseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Pure delegation — verifies this client forwards to EnterpriseService without altering input/output. */
@ExtendWith(MockitoExtension.class)
class CoreEnterpriseClientTest {

  @Mock private EnterpriseService enterpriseService;

  @Test
  void createEnterpriseDelegatesToEnterpriseService() {
    CoreEnterpriseClient client = new CoreEnterpriseClient(enterpriseService);
    EnterpriseCreateRequest request = new EnterpriseCreateRequest();
    EnterpriseResponse expected = EnterpriseResponse.builder().enterpriseId("e1").build();
    when(enterpriseService.createEnterprise(request)).thenReturn(expected);

    assertThat(client.createEnterprise(request)).isSameAs(expected);
  }
}
