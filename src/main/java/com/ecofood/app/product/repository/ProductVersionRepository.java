package com.ecofood.app.product.repository;
import com.ecofood.app.product.entity.ProductVersionEntity;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
/** Compatibility reader used only by ProductProcessStore; active commands do not save here. */
public interface ProductVersionRepository extends MongoRepository<ProductVersionEntity, ObjectId> {
  Optional<ProductVersionEntity> findByProductIdAndVersion(ObjectId productId, int version);
  List<ProductVersionEntity> findByProductIdOrderByVersionDesc(ObjectId productId);
}
