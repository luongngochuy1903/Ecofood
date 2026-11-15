package com.ecofood.app.product.entity;
import com.ecofood.core.entity.BaseEntity;
import java.time.Instant;
import lombok.*;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Document(collection = "product_audit")
public class ProductAuditEntity extends BaseEntity {
  @Field("product_identifier") private ObjectId productId;
  private int version;
  private String action;
  @Field("actor_identifier") private String actorId;
  @Field("event_time") private Instant eventTime;
  private String details;
}
