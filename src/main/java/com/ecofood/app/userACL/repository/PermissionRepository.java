package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.Permission;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PermissionRepository extends MongoRepository<Permission, ObjectId> {

  Optional<Permission> findByCode(String code);
}
