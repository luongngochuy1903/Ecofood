package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.UserEntity;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<UserEntity, ObjectId>, UserRepositoryCustom {

  /**
   * Resolves a verified Keycloak token's email claim back to our own user
   * record — case-insensitive, and keyed on email rather than username
   * because username has no real uniqueness/stability guarantee here (it's
   * an arbitrary string typed at onboarding, and Keycloak doesn't even
   * guarantee it echoes a username claim back in its original case —
   * observed in practice: a user onboarded as "Gamma" got issued tokens
   * carrying preferred_username="gamma"). Email is already enforced unique
   * by both Keycloak and this app's own onboarding validation.
   */
  Optional<UserEntity> findByEmailIgnoreCase(String email);
}
