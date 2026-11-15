package com.ecofood.app.product.repository;
import com.ecofood.app.product.entity.ProductDefinitionAttribute;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface ProductDefinitionAttributeRepository extends MongoRepository<ProductDefinitionAttribute,ObjectId> {}
