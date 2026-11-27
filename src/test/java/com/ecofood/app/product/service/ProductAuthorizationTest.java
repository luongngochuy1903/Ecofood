package com.ecofood.app.product.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.core.client.CoreEnterpriseQueryClient;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.PropositionTenantReference;
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
  private final PropositionRepository propositions = mock(PropositionRepository.class);
  private final CoreEnterpriseQueryClient enterprises = mock(CoreEnterpriseQueryClient.class);
  private final ProductAuthorization authorization =
      new ProductAuthorization(new ACLService(), tenants, enterprises, propositions);
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

  @Test void ecoAdminGetsUnrestrictedPlatformReadWithoutAnyTenantLookup() {
    authenticate("SYSTEM", "ECO_ADMIN");
    var actor = authorization.currentRead();
    assertThat(actor.platform()).isTrue();
    assertThat(authorization.canRead(actor, new ObjectId())).isTrue();
    verifyNoInteractions(tenants);
  }

  @Test void ecoProductManagerIsAllowedButOtherSystemRolesAreForbidden() {
    authenticate("SYSTEM", "ECO_PRODUCT_MANAGER");
    assertThat(authorization.currentRead().platform()).isTrue();
    authenticate("SYSTEM", "ECO_DASHBOARD_MANAGER");
    assertThatThrownBy(authorization::currentRead).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
  }

  @Test void tenantAdminAndTenantProductManagerAreScopedAndOtherTenantRoleStringsAreRejected() {
    authenticate("TENANT", "TENANT_PARTNERSHIP_MANAGER");
    assertThatThrownBy(authorization::currentRead).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    when(tenants.propositionReference(tenant.toHexString())).thenReturn(
        new PropositionTenantReference(tenant.toHexString(), "Sub-Tenant", null, null, null,
            List.of(), List.of(), false));
    for (String role : List.of("TENANT_ADMIN", "TENANT_PRODUCT_MANAGER")) {
      authenticate("TENANT", role);
      assertThat(authorization.currentRead().enterpriseScope()).containsExactly(tenant.toHexString());
    }
  }

  @Test void tenantProductManagerAlsoSeesProductsBehindPropositionsItIsADistributionPartnerOn() {
    authenticate("TENANT", "TENANT_PRODUCT_MANAGER");
    when(tenants.propositionReference(tenant.toHexString())).thenReturn(
        new PropositionTenantReference(tenant.toHexString(), "Partner", null, null, null,
            List.of(), List.of(), false));
    var proposition = new com.ecofood.app.proposition.entity.PropositionEntity();
    var owner = new ObjectId();
    proposition.setEnterpriseId(owner);
    when(propositions.findByDefinitionDistributionPartnerIdsInAndDeletedFalse(List.of(tenant.toHexString())))
        .thenReturn(List.of(proposition));
    var actor = authorization.currentRead();
    assertThat(authorization.canRead(actor, owner)).isTrue();
    assertThat(authorization.canRead(actor, new ObjectId())).isFalse();
  }

  @Test void enterpriseActorCurrentReadIsScopedToItsOwnEnterpriseWhenItOwnsNoRegionalEnterprise() {
    authenticate("ENTERPRISE", "ENTERPRISE_PRODUCT_MANAGER");
    var owner = new ObjectId();
    when(tenants.getById(tenant.toHexString())).thenReturn(TenantResponse.builder().enterpriseId(owner.toHexString()).build());
    when(enterprises.ownedEnterpriseIds(owner.toHexString())).thenReturn(List.of());
    var actor = authorization.currentRead();
    assertThat(actor.platform()).isFalse();
    assertThat(authorization.canRead(actor, owner)).isTrue();
    assertThat(authorization.canRead(actor, new ObjectId())).isFalse();
  }

  @Test void enterpriseActorCurrentReadAlsoSeesProductsOwnedByItsRegionalEnterpriseChildren() {
    authenticate("ENTERPRISE", "ENTERPRISE_PRODUCT_MANAGER");
    var owner = new ObjectId();
    var child = new ObjectId();
    when(tenants.getById(tenant.toHexString())).thenReturn(TenantResponse.builder().enterpriseId(owner.toHexString()).build());
    when(enterprises.ownedEnterpriseIds(owner.toHexString())).thenReturn(List.of(child.toHexString()));
    var actor = authorization.currentRead();
    assertThat(authorization.canRead(actor, owner)).isTrue();
    assertThat(authorization.canRead(actor, child)).isTrue();
    assertThat(authorization.canRead(actor, new ObjectId())).isFalse();
  }
}
