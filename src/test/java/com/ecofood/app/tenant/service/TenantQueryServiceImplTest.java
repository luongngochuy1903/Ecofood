package com.ecofood.app.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ecofood.app.tenant.entity.TenantEntity;
import com.ecofood.app.tenant.repository.TenantRepository;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantQueryServiceImplTest {

  @Mock private TenantRepository tenantRepository;

  private TenantQueryServiceImpl tenantQueryService;

  @Test
  void getByIdReturnsTheEntityWhenFound() {
    tenantQueryService = new TenantQueryServiceImpl(tenantRepository);
    TenantEntity tenant = TenantEntity.builder().tenantName("Acme").build();
    ObjectId id = new ObjectId();
    tenant.setId(id);
    when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));

    assertThat(tenantQueryService.getById(id.toHexString()).getTenantName()).isEqualTo("Acme");
  }

  @Test
  void getByIdThrowsWhenNotFound() {
    tenantQueryService = new TenantQueryServiceImpl(tenantRepository);
    ObjectId id = new ObjectId();
    when(tenantRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> tenantQueryService.getById(id.toHexString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(id.toHexString());
  }

  @Test
  void getByRealmReturnsTheEntityWhenFound() {
    tenantQueryService = new TenantQueryServiceImpl(tenantRepository);
    TenantEntity tenant = TenantEntity.builder().tenantName("Acme").realm("acme-realm").build();
    when(tenantRepository.findByRealm("acme-realm")).thenReturn(Optional.of(tenant));

    assertThat(tenantQueryService.getByRealm("acme-realm").getTenantName()).isEqualTo("Acme");
  }

  @Test
  void getByRealmThrowsWhenNotFound() {
    tenantQueryService = new TenantQueryServiceImpl(tenantRepository);
    when(tenantRepository.findByRealm("missing-realm")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> tenantQueryService.getByRealm("missing-realm"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing-realm");
  }
}
