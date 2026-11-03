package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.UserGroupEntity;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserGroupEntityRepository extends MongoRepository<UserGroupEntity, ObjectId> {

  /** The one UserGroupEntity document for this user — see the entity's own javadoc on the one-doc-per-user model. */
  Optional<UserGroupEntity> findByUserIdentifier(ObjectId userIdentifier);

  /** Every user who currently has this Access Control Group (Data Access Rule) embedded in their own groups[] list. */
  List<UserGroupEntity> findAllByGroups_GroupIdentifier(ObjectId groupIdentifier);
}
