package com.ecofood.app.product.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Base64;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProductAuthorizationTest {
  private final CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
  private final ProductAuthorization authorization = new ProductAuthorization(new ACLService(), tenants);
  private final ObjectId tenant = new ObjectId();

  @AfterEach void cleanup() { AuthenticationContext.unload(); }

  private void authenticate(String role) {
    authenticate(role,role+"_ADMIN");
  }
  private void authenticate(String role,String section) {
    String secret = Base64.getEncoder().encodeToString("product-test-secret-with-at-least-32-bytes".getBytes());
    AuthenticationContext.setSecret(secret);
    AuthenticationContext.setToken(Jwts.builder().subject(new ObjectId().toHexString())
        .claim("tenantId", tenant.toHexString()).claim("roleGroups", List.of(role)).claim("roles", List.of(section))
        .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))).compact());
  }

  @Test void anonymousIsUnauthorized() {
    assertThatThrownBy(authorization::requireEnterprise).isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
    verifyNoInteractions(tenants);
  }

  @Test void tenantRoleIsForbidden() {
    authenticate("TENANT");
    assertThatThrownBy(authorization::requireEnterprise).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    verifyNoInteractions(tenants);
  }

  @Test void enterpriseOwnerIsResolvedFromTenantLookup() {
    authenticate("ENTERPRISE");
    ObjectId enterprise = new ObjectId();
    when(tenants.getById(tenant.toHexString())).thenReturn(TenantResponse.builder().enterpriseId(enterprise.toHexString()).build());
    assertThat(authorization.requireEnterprise()).isEqualTo(enterprise);
  }

  @Test void missingEnterpriseIsForbidden() {
    authenticate("ENTERPRISE");
    when(tenants.getById(tenant.toHexString())).thenReturn(TenantResponse.builder().build());
    assertThatThrownBy(authorization::requireEnterprise).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
  }
  @Test void partnershipAndDashboardManagersCannotUseProductCommandsOrQueries() {
    for(String role:List.of("ENTERPRISE_PARTNERSHIP_MANAGER","ENTERPRISE_DASHBOARD_MANAGER","CUSTOMER")) {
      authenticate("ENTERPRISE",role);
      assertThatThrownBy(authorization::requireEnterprise).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }
    verifyNoInteractions(tenants);
  }
  @Test void enterpriseProductManagerIsAllowed() {
    authenticate("ENTERPRISE","ENTERPRISE_PRODUCT_MANAGER");
    var owner=new ObjectId();when(tenants.getById(tenant.toHexString())).thenReturn(TenantResponse.builder().enterpriseId(owner.toHexString()).build());
    assertThat(authorization.requireEnterprise()).isEqualTo(owner);
  }

  @Test void foreignProductIsHidden() {
    assertThatThrownBy(() -> authorization.requireOwned(new ObjectId(), new ObjectId()))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
  }
}
