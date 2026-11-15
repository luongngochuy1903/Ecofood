package com.ecofood.app.product.entity;
import java.math.BigDecimal;
import lombok.*;
import org.springframework.data.mongodb.core.mapping.Document;
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Document(collection="product_costing_attribute")
public class ProductCostingAttribute extends ProductElementAttribute {
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
