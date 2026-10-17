package com.ecofood.app.enterprise.repository;

import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EnterpriseRepository extends MongoRepository<EnterpriseEntity, ObjectId> {}
