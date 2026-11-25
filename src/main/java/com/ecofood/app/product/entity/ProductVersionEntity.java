package com.ecofood.app.product.entity;
import com.ecofood.core.dto.*;
import com.ecofood.core.entity.BaseEntity;
import com.ecofood.core.enums.ProductStatusEnum;
import java.time.Instant;
import java.util.List;
import lombok.*;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Document(collection = "product_version")
// Legacy snapshots: the active flow writes ProductProcessEntity and typed attributes.
@CompoundIndex(name = "product_version_unique", def = "{'product_identifier': 1, 'version': 1}", unique = true)
public class ProductVersionEntity extends BaseEntity {
  @Field("product_identifier") private ObjectId productId;
  private int version;
  private ProductStatusEnum status;
  private String name;
  private String description;
  private String code;
  private String category;
  private String imageUrl;
  @Builder.Default private List<ProductIngredientRequest> ingredients = List.of();
  private ProductRecipeRequest recipe;
  private ProductPricingRequest pricing;
  @Field("rejection_reason") private String rejectionReason;
  @Field("approved_by") private String approvedBy;
  @Field("approved_at") private Instant approvedAt;
  @Version private Long revision;
}
