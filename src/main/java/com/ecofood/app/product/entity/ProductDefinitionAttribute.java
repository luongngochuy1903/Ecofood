package com.ecofood.app.product.entity;
import com.ecofood.core.dto.*;
import java.util.List;
import lombok.*;
import org.springframework.data.mongodb.core.mapping.Document;
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Document(collection="product_definition_attribute")
public class ProductDefinitionAttribute extends ProductElementAttribute {
  private String productName;
  private String code;
  private String category;
  private String description;
  private String imagesURL;
  private List<ProductIngredientRequest> ingredients;
  private ProductRecipeRequest recipe;
}
