package com.ecofood.app.product.repository;
import com.ecofood.app.product.entity.ProductCostingAttribute;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface ProductCostingAttributeRepository extends MongoRepository<ProductCostingAttribute,ObjectId> {}
