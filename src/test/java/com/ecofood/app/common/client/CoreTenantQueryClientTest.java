package com.ecofood.app.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ecofood.app.common.service.TenantQueryService;
import com.ecofood.app.tenant.entity.TenantEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreTenantQueryClientTest {

  @Mock private TenantQueryService tenantQueryService;

  @Test
  void getByIdDelegatesToTenantQueryService() {
    CoreTenantQueryClient client = new CoreTenantQueryClient(tenantQueryService);
    TenantEntity expected = TenantEntity.builder().tenantName("Acme").build();
    when(tenantQueryService.getById("t1")).thenReturn(expected);

    assertThat(client.getById("t1")).isSameAs(expected);
  }

  @Test
  void getByRealmDelegatesToTenantQueryService() {
    CoreTenantQueryClient client = new CoreTenantQueryClient(tenantQueryService);
    TenantEntity expected = TenantEntity.builder().tenantName("Acme").realm("acme-realm").build();
    when(tenantQueryService.getByRealm("acme-realm")).thenReturn(expected);

    assertThat(client.getByRealm("acme-realm")).isSameAs(expected);
  }
}
