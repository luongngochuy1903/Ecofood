package com.ecofood.app.product.repository;
import com.ecofood.app.product.entity.ProductAuditEntity;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface ProductAuditRepository extends MongoRepository<ProductAuditEntity, ObjectId> { }
