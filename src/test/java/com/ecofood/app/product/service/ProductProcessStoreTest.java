package com.ecofood.app.product.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.repository.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.ProductStatusEnum;
import java.math.BigDecimal;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
class ProductProcessStoreTest {
  private final ProductProcessRepository processes=mock(ProductProcessRepository.class);
  private final ProductDefinitionAttributeRepository definitions=mock(ProductDefinitionAttributeRepository.class);
  private final ProductCostingAttributeRepository costings=mock(ProductCostingAttributeRepository.class);
  private final ProductOverviewAttributeRepository overviews=mock(ProductOverviewAttributeRepository.class);
  private final ProductVersionRepository legacy=mock(ProductVersionRepository.class);
  private final ProductProcessStore store=new ProductProcessStore(processes,definitions,costings,overviews,legacy,
      new ProductProcessSnapshots(new ProductCostCalculator()));
  private ProductProcessEntity process() {
    return ProductProcessEntity.builder().productId(new ObjectId()).version(1).name("Chicken").code("CHICKEN")
        .category("Meals").status(ProductStatusEnum.DRAFT).ingredients(List.of()).componentProducts(List.of())
        .costing(new ProductCostingRequest(new BigDecimal("50000"),new BigDecimal("10000"),null,null,null,new BigDecimal("20"),new BigDecimal("10")))
        .pricing(new ProductPricingRequest(new BigDecimal("90000"),null,"VND")).build();
  }
  @Test void savesSeparateTypedAttributesAndHydratesSameRevision() {
    ProductProcessEntity p=process();
    when(processes.save(any())).thenAnswer(i->{ProductProcessEntity value=i.getArgument(0);value.setRevision(0L);return value;});
    store.save(p);
    verify(definitions).insert(p.getDefinitionAttribute());verify(costings).insert(p.getCostingAttribute());verify(overviews).insert(p.getOverviewAttribute());
    assertThat(p.getOverviewAttribute().getProductName()).isEqualTo("Chicken");
    assertThat(p.getOverviewAttribute().getTotalCost()).isEqualByComparingTo("60000");
    assertThat(p.getOverviewAttribute().getBasePrice()).isEqualByComparingTo("82500");
    when(processes.findByProductIdAndVersion(p.getProductId(),1)).thenReturn(Optional.of(p));
    when(definitions.findById(p.getDefinitionAttribute().getId())).thenReturn(Optional.of(p.getDefinitionAttribute()));
    when(costings.findById(p.getCostingAttribute().getId())).thenReturn(Optional.of(p.getCostingAttribute()));
    when(overviews.findById(p.getOverviewAttribute().getId())).thenReturn(Optional.of(p.getOverviewAttribute()));
    assertThat(store.findByProductIdAndVersion(p.getProductId(),1).orElseThrow().getName()).isEqualTo("Chicken");
  }
  @Test void legacyReadPreservesIdRevisionAndDoesNotWriteOrDelete() {
    ObjectId productId=new ObjectId();
    ProductVersionEntity old=ProductVersionEntity.builder().productId(productId).version(3).revision(7L)
        .name("Existing product").ingredients(List.of()).pricing(new ProductPricingRequest(BigDecimal.TEN,BigDecimal.ONE,"VND")).build();old.setId(new ObjectId());
    // Persisted legacy records include audit fields populated by Mongo auditing.
    ObjectId actorId=new ObjectId();old.setCreatedBy(actorId);old.setUpdatedBy(actorId);
    when(processes.findByProductIdAndVersion(productId,3)).thenReturn(Optional.empty());
    when(legacy.findByProductIdAndVersion(productId,3)).thenReturn(Optional.of(old));
    var result=store.findByProductIdAndVersion(productId,3).orElseThrow();
    assertThat(result.isLegacy()).isTrue();assertThat(result.getId()).isEqualTo(old.getId());assertThat(result.getRevision()).isEqualTo(7L);
    assertThat(result.getName()).isEqualTo(old.getName());assertThat(result.getPricing().sellingPrice()).isEqualByComparingTo("10");
    verify(processes,never()).save(any());verify(processes,never()).insert(any(ProductProcessEntity.class));
    verifyNoInteractions(definitions,costings,overviews);verify(legacy,never()).save(any());verify(legacy,never()).deleteById(any());
  }
  @Test void failedCasRemovesOnlyStagedAttributes() {
    ProductProcessEntity p=process();p.setRevision(4L);
    when(processes.save(any())).thenThrow(new OptimisticLockingFailureException("conflict"));
    assertThatThrownBy(()->store.save(p)).isInstanceOf(OptimisticLockingFailureException.class);
    verify(definitions).deleteById(p.getDefinitionAttribute().getId());verify(costings).deleteById(p.getCostingAttribute().getId());verify(overviews).deleteById(p.getOverviewAttribute().getId());
    assertThat(p.getRevision()).isEqualTo(4L);verifyNoInteractions(legacy);verify(processes,never()).deleteById(any());
  }
  @Test void legacyEditPublishesNewProcessWithoutChangingLegacyRecord() {
    ProductProcessEntity p=process();p.setId(new ObjectId());p.setLegacy(true);p.setRevision(7L);store.save(p);
    verify(processes).insert(p);verify(processes,never()).save(any());verifyNoInteractions(legacy);
    assertThat(p.getRevision()).isEqualTo(8L);assertThat(p.getOverviewAttribute().getProcessRevision()).isEqualTo(8L);
  }
  @Test void unknownPublicationOutcomePreservesAttributesThatMightAlreadyBeReferenced() {
    ProductProcessEntity p=process();p.setRevision(4L);
    when(processes.save(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Network response lost"));
    assertThatThrownBy(()->store.save(p)).hasMessageContaining("Network response lost");
    verify(definitions,never()).deleteById(any());verify(costings,never()).deleteById(any());verify(overviews,never()).deleteById(any());
  }
  @Test void hydrationRejectsAttributesFromAnotherProcess() {
    ProductProcessEntity p=process();
    when(processes.save(any())).thenAnswer(i->{ProductProcessEntity value=i.getArgument(0);value.setRevision(0L);return value;});
    store.save(p);p.getDefinitionAttribute().setProductProcessId(new ObjectId());
    when(processes.findByProductIdAndVersion(p.getProductId(),1)).thenReturn(Optional.of(p));
    when(definitions.findById(p.getDefinitionAttribute().getId())).thenReturn(Optional.of(p.getDefinitionAttribute()));
    when(costings.findById(p.getCostingAttribute().getId())).thenReturn(Optional.of(p.getCostingAttribute()));
    when(overviews.findById(p.getOverviewAttribute().getId())).thenReturn(Optional.of(p.getOverviewAttribute()));
    assertThatThrownBy(()->store.findByProductIdAndVersion(p.getProductId(),1)).hasMessageContaining("another revision");
  }
}
