package com.ecofood.app.product.entity;
import com.ecofood.core.entity.BaseEntity;
import com.ecofood.core.enums.ProductTypeEnum;
import com.ecofood.core.enums.ProductStatusEnum;
import lombok.*;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Document(collection = "product")
public class ProductEntity extends BaseEntity {
  /** Name of this entity's atomic Mongo counter — same pattern as TenantEntity.SEQUENCE_NAME. */
  public static final String SEQUENCE_NAME = "product_sequence";

  @Indexed @Field("enterprise_identifier") private ObjectId enterpriseId;
  @Field("product_type") private ProductTypeEnum type;
  @Field("product_code") private String code;
  @Field("latest_version") private int latestVersion;
  @Field("latest_status") private ProductStatusEnum latestStatus;
  private String description;
  @Builder.Default private java.util.List<String> category = java.util.List.of();
  private ProductStatusEnum productStatus;
  @Builder.Default private java.util.List<ObjectId> productProcess = java.util.List.of();
  @Version private Long revision;
  /** Durable cascade work committed with the new latest-version pointer. */
  @Builder.Default private java.util.List<Integer> pendingVersionReviews = java.util.List.of();
}
