package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.*;
import com.ecofood.core.client.CoreEnterpriseQueryClient;
import com.ecofood.core.client.CoreUserQueryClient;
import com.ecofood.core.dto.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class ProductProcessMapper {
  private final CoreEnterpriseQueryClient enterpriseQueryClient;
  private final CoreUserQueryClient userQueryClient;
  ProductResponse toResponse(ProductEntity product,ProductProcessEntity v) {return toResponse(product,v,List.of(v));}
  ProductResponse toResponse(ProductEntity product,ProductProcessEntity v,List<ProductProcessEntity> processes) {
    prepare(v);
    // Cross-module name lookup — never calls enterprise's own service/repository
    // directly, per this project's module-boundary pattern (see CoreEnterpriseQueryClient).
    String enterpriseName = enterpriseQueryClient.getById(product.getEnterpriseId().toHexString()).getEnterpriseCompanyName();
    return new ProductResponse(product.getIdAsString(),product.getEnterpriseId().toHexString(),enterpriseName,product.getType(),
        v.getVersion(),v.getRevision(),v.getStatus(),
        v.getName(),v.getDescription(),v.getIngredients(),v.getRecipe(),v.getPricing(),v.getRejectionReason(),approverName(v.getApprovedBy()),v.getApprovedAt(),
        v.getCreatedAt(),v.getUpdatedAt(),v.getCode(),v.getCategory(),v.getImageUrl(),
        costing(v.getCostingAttribute()),processes.stream().map(this::process).toList());
  }
  static ProductVersionResponse version(ProductProcessEntity v) {return new ProductVersionResponse(v.getVersion(),v.getRevision(),v.getStatus(),v.getName(),v.getCreatedAt(),v.getUpdatedAt());}
  private static void prepare(ProductProcessEntity p) {
    if(p.getDefinitionAttribute()==null||p.getOverviewAttribute()==null)
      new ProductProcessSnapshots(new ProductCostCalculator()).prepare(p);
  }
  public ProductProcessResponse process(ProductProcessEntity p) {
    prepare(p);
    return new ProductProcessResponse(p.getIdAsString(),p.getProductId().toHexString(),p.getVersion(),p.getRevision(),p.getStatus(),p.getProcessElementName(),
        new ProductProcessElementResponse<>(p.getDefinition()==null?null:p.getDefinition().id(),"Definition",List.of(definition(p.getDefinitionAttribute()))),
        new ProductProcessElementResponse<>(p.getCostingProcess()==null?null:p.getCostingProcess().id(),"Costing",p.getCostingAttribute()==null?List.of():List.of(costing(p.getCostingAttribute()))),
        new ProductProcessElementResponse<>(p.getOverview()==null?null:p.getOverview().id(),"Overview",List.of(overview(p.getOverviewAttribute()))),
        p.isLegacy(),p.getRejectionReason(),approverName(p.getApprovedBy()),p.getApprovedAt(),p.getCreatedAt(),p.getUpdatedAt());
  }
  /**
   * Resolves the approving user's id into "Lastname Firstname" — cross-module
   * lookup via CoreUserQueryClient, never userACL's own repository/service
   * directly (see this project's module-boundary pattern). Falls back to the
   * raw id if the user can no longer be found (e.g. deleted).
   */
  private String approverName(String approvedBy) {
    if(approvedBy==null || approvedBy.isBlank()) return approvedBy;
    try {
      var user=userQueryClient.getById(approvedBy);
      String last=user.getLastName()==null?"":user.getLastName().trim();
      String first=user.getFirstName()==null?"":user.getFirstName().trim();
      String name=(last+" "+first).trim();
      return name.isEmpty()?approvedBy:name;
    } catch(RuntimeException notFound) { return approvedBy; }
  }
  private static String processId(ProductElementAttribute a) {return a.getProductProcessId()==null?null:a.getProductProcessId().toHexString();}
  private static ProductDefinitionAttributeResponse definition(ProductDefinitionAttribute d) {
    return new ProductDefinitionAttributeResponse(d.getIdAsString(),processId(d),d.getProcessRevision(),d.getProductName(),d.getCode(),d.getCategory(),d.getDescription(),d.getImagesURL(),d.getIngredients(),d.getRecipe());
  }
  private static ProductCostingAttributeResponse costing(ProductCostingAttribute c) {
    if(c==null) return null;
    return new ProductCostingAttributeResponse(c.getIdAsString(),processId(c),c.getProcessRevision(),c.getIngredientCost(),c.getPackagingCost(),c.getLaborCost(),c.getOperationCost(),c.getMarketingFee(),c.getTargetMargin(),c.getTax(),c.getTotalCost(),c.getBasePrice(),c.getSellingPrice(),c.getCurrency(),c.getNote());
  }
  private static ProductOverviewAttributeResponse overview(ProductOverviewAttribute o) {
    return new ProductOverviewAttributeResponse(o.getIdAsString(),processId(o),o.getProcessRevision(),o.getProductName(),o.getCode(),o.getCategory(),o.getDescription(),o.getImagesURL(),o.getIngredients(),o.getRecipe(),o.getIngredientCost(),o.getPackagingCost(),o.getLaborCost(),o.getOperationCost(),o.getMarketingFee(),o.getTargetMargin(),o.getTax(),o.getTotalCost(),o.getBasePrice(),o.getSellingPrice(),o.getCurrency(),o.getNote());
  }
}
