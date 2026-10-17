package com.ecofood.app.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ecofood.app.common.service.EnterpriseQueryService;
import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreEnterpriseQueryClientTest {

  @Mock private EnterpriseQueryService enterpriseQueryService;

  @Test
  void getByIdDelegatesToEnterpriseQueryService() {
    CoreEnterpriseQueryClient client = new CoreEnterpriseQueryClient(enterpriseQueryService);
    EnterpriseEntity expected = EnterpriseEntity.builder().partyName("Acme").build();
    when(enterpriseQueryService.getById("e1")).thenReturn(expected);

    assertThat(client.getById("e1")).isSameAs(expected);
  }
}
