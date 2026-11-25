package com.ecofood.app.product.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.repository.*;
import com.ecofood.core.client.CoreEnterpriseQueryClient;
import com.ecofood.core.client.CoreUserQueryClient;
import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.enums.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

class ProductQueryServiceImplTest {
  private final ProductRepository products = mock(ProductRepository.class);
  private final ProductProcessStore versions = mock(ProductProcessStore.class);
  private final ProductAuthorization auth = mock(ProductAuthorization.class);
  private final CoreEnterpriseQueryClient enterpriseQueryClient = mock(CoreEnterpriseQueryClient.class);
  private final CoreUserQueryClient userQueryClient = mock(CoreUserQueryClient.class);
  private final ProductQueryServiceImpl service = new ProductQueryServiceImpl(products, versions, auth,
      new ProductProcessMapper(enterpriseQueryClient, userQueryClient));
  private final ObjectId owner = new ObjectId();
  {
    when(enterpriseQueryClient.getById(any())).thenReturn(
        EnterpriseResponse.builder().enterpriseId("e").enterpriseCompanyName("Test Enterprise").build());
  }

  private ProductEntity product(String name, ProductStatusEnum status) {
    ProductEntity p = ProductEntity.builder().enterpriseId(owner).type(ProductTypeEnum.STANDALONE).latestVersion(1).build();
    p.setId(new ObjectId());
    ProductProcessEntity v = ProductProcessEntity.builder().productId(p.getId()).version(1).name(name).status(status).build();
    when(versions.findByProductIdAndVersion(p.getId(), 1)).thenReturn(Optional.of(v));
    return p;
  }

  @Test void listFiltersLatestVersionThenPaginatesWithCorrectTotal() {
    when(auth.currentRead()).thenReturn(new ProductAuthorization.ReadActor(Set.of(owner.toHexString()), false));
    ProductEntity a = product("Milk 1L", ProductStatusEnum.APPROVED);
    ProductEntity b = product("Milk 2L", ProductStatusEnum.APPROVED);
    ProductEntity c = product("Milk draft", ProductStatusEnum.DRAFT);
    when(products.findByEnterpriseIdIn(List.of(owner))).thenReturn(List.of(a, b, c));
    var page = service.list(" MILK ", ProductTypeEnum.STANDALONE, ProductStatusEnum.APPROVED, PageRequest.of(1, 1));
    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent()).hasSize(1);
    assertThat(service.list(null, null, null, Pageable.unpaged()).getContent()).hasSize(3);
  }

  @Test void approvedHistoricalVersionRemainsAvailableWhenLatestIsDraft() {
    when(auth.currentRead()).thenReturn(new ProductAuthorization.ReadActor(Set.of(owner.toHexString()), false));
    when(auth.canRead(any(), eq(owner))).thenReturn(true);
    ProductEntity p = product("Approved milk", ProductStatusEnum.APPROVED);
    p.setLatestVersion(2);
    when(products.findById(p.getId())).thenReturn(Optional.of(p));
    assertThat(service.getApprovedVersion(p.getIdAsString(), 1).status()).isEqualTo(ProductStatusEnum.APPROVED);
  }

  @Test void draftCannotBeUsedAsApprovedVersion() {
    when(auth.currentRead()).thenReturn(new ProductAuthorization.ReadActor(Set.of(owner.toHexString()), false));
    when(auth.canRead(any(), eq(owner))).thenReturn(true);
    ProductEntity p = product("Draft milk", ProductStatusEnum.DRAFT);
    when(products.findById(p.getId())).thenReturn(Optional.of(p));
    assertThatThrownBy(() -> service.getApprovedVersion(p.getIdAsString(), 1))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
  }

  @Test void deletedProductIsHidden() {
    ProductEntity p = ProductEntity.builder().enterpriseId(owner).build();
    p.setId(new ObjectId()); p.setDeleted(true);
    when(products.findById(p.getId())).thenReturn(Optional.of(p));
    assertThatThrownBy(() -> service.getById(p.getIdAsString()))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    verifyNoInteractions(versions);
  }

  @Test void productOutsideActorsScopeIsHidden() {
    when(auth.currentRead()).thenReturn(new ProductAuthorization.ReadActor(Set.of(new ObjectId().toHexString()), false));
    ProductEntity p = product("Someone else's milk", ProductStatusEnum.APPROVED);
    when(products.findById(p.getId())).thenReturn(Optional.of(p));
    assertThatThrownBy(() -> service.getById(p.getIdAsString()))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
  }

  @Test void platformActorSeesProductsAcrossEveryEnterprise() {
    when(auth.currentRead()).thenReturn(new ProductAuthorization.ReadActor(Set.of(), true));
    when(auth.canRead(any(), any())).thenReturn(true);
    ProductEntity a = product("Milk from Enterprise A", ProductStatusEnum.APPROVED);
    when(products.findAll()).thenReturn(List.of(a));
    assertThat(service.list(null, null, null, Pageable.unpaged()).getContent()).hasSize(1);
    when(products.findById(a.getId())).thenReturn(Optional.of(a));
    assertThat(service.getById(a.getIdAsString())).isNotNull();
  }
}
