package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.enums.TenantTypeEnum;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CustomerTenantResolverTest {
  private final CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
  private final CustomerAuthProperties properties = CustomerIdentityServiceTest.properties();
  private final CustomerTenantResolver resolver = new CustomerTenantResolver(properties, tenants);

  static TenantResponse tenant() { return TenantResponse.builder().id(CustomerIdentityServiceTest.TENANT)
      .tenantName("CustomerPortal").realm(CustomerIdentityServiceTest.REALM).clientId(CustomerIdentityServiceTest.CLIENT)
      .tenantType(TenantTypeEnum.STANDALONE).build(); }

  @Test void derivesRealmAndClientOnlyFromConfiguredOnboardedTenant() {
    var tenant = tenant(); when(tenants.getById(tenant.getId())).thenReturn(tenant);
    when(tenants.getAllByRealm(tenant.getRealm())).thenReturn(List.of(tenant));
    var portal = resolver.resolve();
    assertEquals(CustomerIdentityServiceTest.portal(), portal);
    tenant.setRealm("customerportal-new"); tenant.setClientId("onboarded-public-client");
    when(tenants.getAllByRealm(tenant.getRealm())).thenReturn(List.of(tenant));
    assertEquals("https://identity.example.test/realms/customerportal-new", resolver.resolve().issuer());
    assertEquals("onboarded-public-client", resolver.resolve().clientId());
  }

  @Test void missingInvalidSubtenantOrSharedRealmIsUnavailableRatherThanFallingBackToMaster() {
    assertThrows(ResponseStatusException.class, resolver::resolve);
    var tenant = tenant(); when(tenants.getById(tenant.getId())).thenReturn(tenant);
    tenant.setTenantType(TenantTypeEnum.SUB_TENANT);
    assertThrows(ResponseStatusException.class, resolver::resolve);
    tenant.setTenantType(TenantTypeEnum.STANDALONE);
    when(tenants.getAllByRealm(tenant.getRealm())).thenReturn(List.of(tenant, TenantResponse.builder().id("other").build()));
    assertThrows(ResponseStatusException.class, resolver::resolve);
    tenant.setRealm("master");
    assertThrows(ResponseStatusException.class, resolver::resolve);
    properties.setTenantId("");
    assertThrows(ResponseStatusException.class, resolver::resolve);
  }
}
