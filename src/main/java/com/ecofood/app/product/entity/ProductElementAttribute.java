package com.ecofood.app.product.entity;
import com.ecofood.core.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.index.Indexed;
@Getter @Setter
public abstract class ProductElementAttribute extends BaseEntity {
  @Indexed private ObjectId productProcessId;
  private Long processRevision;
}
