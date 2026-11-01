package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.Role;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RoleRepository extends MongoRepository<Role, ObjectId> {

  Optional<Role> findByCode(String code);

  /** Every role whose code starts with the given actor prefix (e.g. "ENTERPRISE_") — see RoleService.listByActor. */
  List<Role> findByCodeStartingWith(String prefix);
}
