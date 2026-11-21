package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.*;
import com.ecofood.core.dto.*;
import java.util.List;
final class ProductMapper {
  private ProductMapper() {}
  static ProductResponse toResponse(ProductEntity product, ProductVersionEntity v) {
    return new ProductResponse(product.getIdAsString(), product.getEnterpriseId().toHexString(),
        product.getType(), product.getSourceProductId()==null?null:product.getSourceProductId().toHexString(),
        v.getVersion(), v.getRevision(), v.getStatus(), v.getName(), v.getDescription(),
        v.getIngredients() == null ? List.of() : List.copyOf(v.getIngredients()),
        v.getRecipe(), v.getPricing(), v.getRejectionReason(), v.getApprovedBy(), v.getApprovedAt(),
        v.getCreatedAt(), v.getUpdatedAt(), v.getCode(), v.getCategory(), v.getImageUrl(),
        v.getComponentProducts() == null ? List.of() : List.copyOf(v.getComponentProducts()));
  }
  static ProductVersionResponse version(ProductVersionEntity v) {
    return new ProductVersionResponse(v.getVersion(),v.getRevision(),v.getStatus(),
                                      v.getName(),v.getCreatedAt(),v.getUpdatedAt());
  }
}
