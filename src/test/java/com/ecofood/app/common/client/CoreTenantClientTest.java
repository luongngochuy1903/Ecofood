package com.ecofood.app.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ecofood.app.common.dto.TenantOnboardRequest;
import com.ecofood.app.common.dto.TenantOnboardResponse;
import com.ecofood.app.common.service.TenantService;
import com.ecofood.app.tenant.entity.TenantEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreTenantClientTest {

  @Mock private TenantService tenantService;

  @Test
  void onboardTenantDelegatesToTenantService() {
    CoreTenantClient client = new CoreTenantClient(tenantService);
    TenantOnboardRequest request = new TenantOnboardRequest();
    TenantOnboardResponse expected = TenantOnboardResponse.builder().tenantId("t1").build();
    when(tenantService.onboardTenant(request)).thenReturn(expected);

    assertThat(client.onboardTenant(request)).isSameAs(expected);
  }

  @Test
  void getByIdDelegatesToTenantService() {
    CoreTenantClient client = new CoreTenantClient(tenantService);
    TenantEntity expected = TenantEntity.builder().tenantName("Acme").build();
    when(tenantService.getById("t1")).thenReturn(expected);

    assertThat(client.getById("t1")).isSameAs(expected);
  }
}
