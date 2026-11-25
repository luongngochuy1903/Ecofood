package com.ecofood.app.product.entity;
import com.ecofood.core.dto.*;
import java.math.BigDecimal;
import java.util.List;
import lombok.*;
import org.springframework.data.mongodb.core.mapping.Document;
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Document(collection="product_overview_attribute")
public class ProductOverviewAttribute extends ProductElementAttribute {
  private String productName;
  private String code;
  private String category;
  private String description;
  private String imagesURL;
  private List<ProductIngredientRequest> ingredients;
  private ProductRecipeRequest recipe;
  private BigDecimal ingredientCost;
  private BigDecimal packagingCost;
  private BigDecimal laborCost;
  private BigDecimal operationCost;
  private BigDecimal marketingFee;
  private BigDecimal targetMargin;
  private BigDecimal tax;
  private BigDecimal totalCost;
  private BigDecimal basePrice;
  private BigDecimal sellingPrice;
  private String currency;
  private String note;
}
