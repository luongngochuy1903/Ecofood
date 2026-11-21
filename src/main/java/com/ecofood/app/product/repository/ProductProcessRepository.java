package com.ecofood.app.product.repository;
import com.ecofood.app.product.entity.ProductProcessEntity;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface ProductProcessRepository extends MongoRepository<ProductProcessEntity,ObjectId> {
  Optional<ProductProcessEntity> findByProductIdAndVersion(ObjectId productId,int version);
  List<ProductProcessEntity> findByProductIdOrderByVersionDesc(ObjectId productId);
}
