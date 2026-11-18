package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.*;
import com.ecofood.core.dto.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

/** Builds all three attributes from a single process revision so Overview cannot drift. */
@Component @RequiredArgsConstructor
public class ProductProcessSnapshots {
  private final ProductCostCalculator calculator;
  public void prepare(ProductProcessEntity p) {
    ProductDefinitionAttribute definition=ProductDefinitionAttribute.builder().productName(p.getName()).code(p.getCode())
        .category(p.getCategory()).description(p.getDescription()).imagesURL(p.getImageUrl())
        .ingredients(p.getIngredients()==null?List.of():List.copyOf(p.getIngredients()))
        .componentProducts(p.getComponentProducts()==null?List.of():List.copyOf(p.getComponentProducts()))
        .recipe(p.getRecipe()).build();
    ProductCostingAttribute costing=calculator.calculate(p.getCosting(),p.getPricing());
    ProductOverviewAttribute overview=new ProductOverviewAttribute();
    String[] metadata={"id","productProcessId","processRevision","createdAt","updatedAt","createdBy","updatedBy","deleted"};
    BeanUtils.copyProperties(definition,overview,metadata);
    BeanUtils.copyProperties(costing,overview,metadata);
    p.setDefinitionAttribute(definition); p.setCostingAttribute(costing); p.setOverviewAttribute(overview);
    if(p.getProcessElementName()==null)p.setProcessElementName("Version "+p.getVersion());
    // Flat API fields are a projection, not another stored copy of the definition.
    project(p,definition,costing,overview);
  }
  public void project(ProductProcessEntity p,ProductDefinitionAttribute d,ProductCostingAttribute c,ProductOverviewAttribute o) {
    p.setDefinitionAttribute(d);p.setCostingAttribute(c);p.setOverviewAttribute(o);
    p.setName(d.getProductName());p.setCode(d.getCode());p.setCategory(d.getCategory());
    p.setDescription(d.getDescription());p.setImageUrl(d.getImagesURL());p.setIngredients(d.getIngredients());
    p.setComponentProducts(d.getComponentProducts());p.setRecipe(d.getRecipe());
    p.setCosting(new ProductCostingRequest(c.getIngredientCost(),c.getPackagingCost(),c.getLaborCost(),c.getOperationCost(),c.getMarketingFee(),c.getTargetMargin(),c.getTax(),c.getCurrency()));
    p.setPricing(c.getSellingPrice()==null?null:new ProductPricingRequest(c.getSellingPrice(),c.getTotalCost(),c.getCurrency(),c.getNote()));
  }
}
