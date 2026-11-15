package com.ecofood.app.product.service;
import static org.assertj.core.api.Assertions.*;
import com.ecofood.app.product.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
class ProductProcessMappingTest {
  @Test void processPersistsGroupReferencesInsteadOfFlattenedVersionFields() throws Exception {
    MongoMappingContext context=new MongoMappingContext();
    context.setSimpleTypeHolder(MongoCustomConversions.create(adapter -> {}).getSimpleTypeHolder());
    context.afterPropertiesSet();
    var process=context.getRequiredPersistentEntity(ProductProcessEntity.class);
    assertThat(process.getCollection()).isEqualTo("product_process");
    assertThat(process.getPersistentProperty("definition")).isNotNull();
    assertThat(process.getPersistentProperty("costingProcess")).isNotNull();
    assertThat(process.getPersistentProperty("overview")).isNotNull();
    for(String projection:new String[]{"name","category","ingredients","recipe","pricing","costingAttribute","overviewAttribute"})
      assertThat(process.getPersistentProperty(projection)).as(projection+" is not persisted on process").isNull();
    assertThat(context.getRequiredPersistentEntity(ProductDefinitionAttribute.class).getCollection()).isEqualTo("product_definition_attribute");
    assertThat(context.getRequiredPersistentEntity(ProductCostingAttribute.class).getCollection()).isEqualTo("product_costing_attribute");
    assertThat(context.getRequiredPersistentEntity(ProductOverviewAttribute.class).getCollection()).isEqualTo("product_overview_attribute");
  }
}
