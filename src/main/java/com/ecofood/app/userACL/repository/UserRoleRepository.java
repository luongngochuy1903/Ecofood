package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.UserRole;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRoleRepository extends MongoRepository<UserRole, ObjectId> {

  Optional<UserRole> findByUserIdAndTenantId(ObjectId userId, ObjectId tenantId);
}
