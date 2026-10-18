package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.RolePermission;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RolePermissionRepository extends MongoRepository<RolePermission, ObjectId> {

  Optional<RolePermission> findByRoleId(ObjectId roleId);
}
