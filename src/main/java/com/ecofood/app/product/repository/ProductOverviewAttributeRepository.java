package com.ecofood.app.product.repository;
import com.ecofood.app.product.entity.ProductOverviewAttribute;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface ProductOverviewAttributeRepository extends MongoRepository<ProductOverviewAttribute,ObjectId> {}
