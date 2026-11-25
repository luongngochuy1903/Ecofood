package com.ecofood.app.product.entity;
import com.ecofood.core.dto.*;
import com.ecofood.core.entity.BaseEntity;
import com.ecofood.core.enums.ProductStatusEnum;
import java.time.Instant;
import java.util.List;
import lombok.*;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Version;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** One process represents one version; detail fields are projections of its linked attributes. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Document(collection="product_process")
@CompoundIndex(name="product_process_version_unique", def="{'product_identifier':1,'version':1}", unique=true)
public class ProductProcessEntity extends BaseEntity {
  @Field("product_identifier") private ObjectId productId;
  private int version;
  private String processElementName;
  private ProductStatusEnum status;
  private ProductDefinition definition;
  private ProductCosting costingProcess;
  private ProductOverview overview;
  private String rejectionReason;
  private String approvedBy;
  private Instant approvedAt;
  @Version private Long revision;
  @Transient private boolean legacy;
  @Transient private String name;
  @Transient private String code;
  @Transient private String category;
  @Transient private String description;
  @Transient private String imageUrl;
  @Transient @Builder.Default private List<ProductIngredientRequest> ingredients=List.of();
  @Transient private ProductRecipeRequest recipe;
  @Transient private ProductPricingRequest pricing;
  @Transient private ProductCostingRequest costing;
  @Transient private ProductDefinitionAttribute definitionAttribute;
  @Transient private ProductCostingAttribute costingAttribute;
  @Transient private ProductOverviewAttribute overviewAttribute;
}
