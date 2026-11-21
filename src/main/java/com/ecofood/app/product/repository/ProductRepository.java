package com.ecofood.app.product.repository;
import com.ecofood.app.product.entity.ProductEntity;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface ProductRepository extends MongoRepository<ProductEntity, ObjectId> {
  java.util.List<ProductEntity> findByEnterpriseId(ObjectId enterpriseId);
  java.util.Optional<ProductEntity> findByEnterpriseIdAndCode(ObjectId enterpriseId, String code);
}
