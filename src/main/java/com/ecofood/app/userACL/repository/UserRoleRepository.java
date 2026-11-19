package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.UserRole;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRoleRepository extends MongoRepository<UserRole, ObjectId> {

  Optional<UserRole> findByUserIdAndTenantId(ObjectId userId, ObjectId tenantId);

  /** Every tenant the user holds a role on — backs the "Edit User" modal's Access & Permissions section. */
  List<UserRole> findAllByUserId(ObjectId userId);

  /** Revokes a tenant assignment entirely — see UserServiceImpl.assignTenantRoles's removal pass. */
  void deleteByUserIdAndTenantId(ObjectId userId, ObjectId tenantId);
}
