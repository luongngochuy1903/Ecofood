package com.ecofood.app.product.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.repository.*;
import com.ecofood.core.enums.*;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

class ProductQueryServiceImplTest {
  private final ProductRepository products = mock(ProductRepository.class);
  private final ProductProcessStore versions = mock(ProductProcessStore.class);
  private final ProductAuthorization auth = mock(ProductAuthorization.class);
  private final ProductQueryServiceImpl service = new ProductQueryServiceImpl(products, versions, auth);
  private final ObjectId owner = new ObjectId();

  private ProductEntity product(String name, ProductStatusEnum status) {
    ProductEntity p = ProductEntity.builder().enterpriseId(owner).type(ProductTypeEnum.MASTER).latestVersion(1).build();
    p.setId(new ObjectId());
    ProductProcessEntity v = ProductProcessEntity.builder().productId(p.getId()).version(1).name(name).status(status).build();
    when(versions.findByProductIdAndVersion(p.getId(), 1)).thenReturn(Optional.of(v));
    return p;
  }

  @Test void listFiltersLatestVersionThenPaginatesWithCorrectTotal() {
    when(auth.requireEnterprise()).thenReturn(owner);
    ProductEntity a = product("Milk 1L", ProductStatusEnum.APPROVED);
    ProductEntity b = product("Milk 2L", ProductStatusEnum.APPROVED);
    ProductEntity c = product("Milk draft", ProductStatusEnum.DRAFT);
    when(products.findByEnterpriseId(owner)).thenReturn(List.of(a, b, c));
    var page = service.list(" MILK ", ProductTypeEnum.MASTER, ProductStatusEnum.APPROVED, PageRequest.of(1, 1));
    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent()).hasSize(1);
    assertThat(service.list(null, null, null, Pageable.unpaged()).getContent()).hasSize(3);
  }

  @Test void approvedHistoricalVersionRemainsAvailableWhenLatestIsDraft() {
    when(auth.requireEnterprise()).thenReturn(owner);
    ProductEntity p = product("Approved milk", ProductStatusEnum.APPROVED);
    p.setLatestVersion(2);
    when(products.findById(p.getId())).thenReturn(Optional.of(p));
    assertThat(service.getApprovedVersion(p.getIdAsString(), 1).status()).isEqualTo(ProductStatusEnum.APPROVED);
  }

  @Test void draftCannotBeUsedAsApprovedVersion() {
    when(auth.requireEnterprise()).thenReturn(owner);
    ProductEntity p = product("Draft milk", ProductStatusEnum.DRAFT);
    when(products.findById(p.getId())).thenReturn(Optional.of(p));
    assertThatThrownBy(() -> service.getApprovedVersion(p.getIdAsString(), 1))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
  }

  @Test void deletedProductIsHidden() {
    when(auth.requireEnterprise()).thenReturn(owner);
    ProductEntity p = ProductEntity.builder().enterpriseId(owner).build();
    p.setId(new ObjectId()); p.setDeleted(true);
    when(products.findById(p.getId())).thenReturn(Optional.of(p));
    assertThatThrownBy(() -> service.getById(p.getIdAsString()))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    verifyNoInteractions(versions);
  }
}
