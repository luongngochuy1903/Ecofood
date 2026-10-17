package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.Role;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RoleRepository extends MongoRepository<Role, ObjectId> {

  Optional<Role> findByCode(String code);
}
