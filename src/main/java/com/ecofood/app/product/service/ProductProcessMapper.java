package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.*;
import com.ecofood.core.dto.*;
import java.util.List;
final class ProductProcessMapper {
  private ProductProcessMapper() {}
  static ProductResponse toResponse(ProductEntity product,ProductProcessEntity v) {return toResponse(product,v,List.of(v));}
  static ProductResponse toResponse(ProductEntity product,ProductProcessEntity v,List<ProductProcessEntity> processes) {
    prepare(v);
    return new ProductResponse(product.getIdAsString(),product.getEnterpriseId().toHexString(),product.getType(),
        product.getSourceProductId()==null?null:product.getSourceProductId().toHexString(),v.getVersion(),v.getRevision(),v.getStatus(),
        v.getName(),v.getDescription(),v.getIngredients(),v.getRecipe(),v.getPricing(),v.getRejectionReason(),v.getApprovedBy(),v.getApprovedAt(),
        v.getCreatedAt(),v.getUpdatedAt(),v.getCode(),v.getCategory(),v.getImageUrl(),v.getComponentProducts(),
        costing(v.getCostingAttribute()),processes.stream().map(ProductProcessMapper::process).toList());
  }
  static ProductVersionResponse version(ProductProcessEntity v) {return new ProductVersionResponse(v.getVersion(),v.getRevision(),v.getStatus(),v.getName(),v.getCreatedAt(),v.getUpdatedAt());}
  private static void prepare(ProductProcessEntity p) {
    if(p.getDefinitionAttribute()==null||p.getCostingAttribute()==null||p.getOverviewAttribute()==null)
      new ProductProcessSnapshots(new ProductCostCalculator()).prepare(p);
  }
  static ProductProcessResponse process(ProductProcessEntity p) {
    prepare(p);
    return new ProductProcessResponse(p.getIdAsString(),p.getProductId().toHexString(),p.getVersion(),p.getRevision(),p.getStatus(),p.getProcessElementName(),
        new ProductProcessElementResponse<>(p.getDefinition()==null?null:p.getDefinition().id(),"Definition",List.of(definition(p.getDefinitionAttribute()))),
        new ProductProcessElementResponse<>(p.getCostingProcess()==null?null:p.getCostingProcess().id(),"Costing",List.of(costing(p.getCostingAttribute()))),
        new ProductProcessElementResponse<>(p.getOverview()==null?null:p.getOverview().id(),"Overview",List.of(overview(p.getOverviewAttribute()))),
        p.isLegacy(),p.getRejectionReason(),p.getApprovedBy(),p.getApprovedAt(),p.getCreatedAt(),p.getUpdatedAt());
  }
  private static String processId(ProductElementAttribute a) {return a.getProductProcessId()==null?null:a.getProductProcessId().toHexString();}
  private static ProductDefinitionAttributeResponse definition(ProductDefinitionAttribute d) {
    return new ProductDefinitionAttributeResponse(d.getIdAsString(),processId(d),d.getProcessRevision(),d.getProductName(),d.getCode(),d.getCategory(),d.getDescription(),d.getImagesURL(),d.getIngredients(),d.getComponentProducts(),d.getRecipe());
  }
  private static ProductCostingAttributeResponse costing(ProductCostingAttribute c) {
    return new ProductCostingAttributeResponse(c.getIdAsString(),processId(c),c.getProcessRevision(),c.getIngredientCost(),c.getPackagingCost(),c.getLaborCost(),c.getOperationCost(),c.getMarketingFee(),c.getTargetMargin(),c.getTax(),c.getTotalCost(),c.getBasePrice(),c.getSellingPrice(),c.getCurrency(),c.getNote());
  }
  private static ProductOverviewAttributeResponse overview(ProductOverviewAttribute o) {
    return new ProductOverviewAttributeResponse(o.getIdAsString(),processId(o),o.getProcessRevision(),o.getProductName(),o.getCode(),o.getCategory(),o.getDescription(),o.getImagesURL(),o.getIngredients(),o.getComponentProducts(),o.getRecipe(),o.getIngredientCost(),o.getPackagingCost(),o.getLaborCost(),o.getOperationCost(),o.getMarketingFee(),o.getTargetMargin(),o.getTax(),o.getTotalCost(),o.getBasePrice(),o.getSellingPrice(),o.getCurrency(),o.getNote());
  }
}
