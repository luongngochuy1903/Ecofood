package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.UserEntity;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<UserEntity, ObjectId> {

  /** Resolves a verified Keycloak token's preferred_username back to our own user record. */
  Optional<UserEntity> findByUsername(String username);
}
