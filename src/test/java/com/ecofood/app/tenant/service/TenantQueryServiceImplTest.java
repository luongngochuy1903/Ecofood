package com.ecofood.app.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ecofood.app.tenant.entity.TenantEntity;
import com.ecofood.app.tenant.repository.TenantRepository;
import java.util.List;
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
  void getAllByRealmReturnsEveryTenantHostedInIt() {
    tenantQueryService = new TenantQueryServiceImpl(tenantRepository);
    TenantEntity master = TenantEntity.builder().tenantName("Gamma Hub").realm("gamma-realm").build();
    TenantEntity subTenant = TenantEntity.builder().tenantName("Fresh Supply").realm("gamma-realm").build();
    when(tenantRepository.findAllByRealm("gamma-realm")).thenReturn(List.of(master, subTenant));

    assertThat(tenantQueryService.getAllByRealm("gamma-realm"))
        .extracting("tenantName")
        .containsExactly("Gamma Hub", "Fresh Supply");
  }

  @Test
  void getAllByRealmReturnsEmptyListWhenNoTenantHostsThatRealm() {
    tenantQueryService = new TenantQueryServiceImpl(tenantRepository);
    when(tenantRepository.findAllByRealm("missing-realm")).thenReturn(List.of());

    assertThat(tenantQueryService.getAllByRealm("missing-realm")).isEmpty();
  }

  @Test
  void getScopedTenantIdsReturnsHexStringsFromTheRepository() {
    tenantQueryService = new TenantQueryServiceImpl(tenantRepository);
    ObjectId selfId = new ObjectId();
    ObjectId subTenantId = new ObjectId();
    when(tenantRepository.findScopedTenantIds(selfId)).thenReturn(List.of(selfId, subTenantId));

    assertThat(tenantQueryService.getScopedTenantIds(selfId.toHexString()))
        .containsExactly(selfId.toHexString(), subTenantId.toHexString());
  }
}
