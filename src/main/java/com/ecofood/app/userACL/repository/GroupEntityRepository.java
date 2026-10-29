package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.GroupEntity;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GroupEntityRepository extends MongoRepository<GroupEntity, ObjectId> {

  /** Every (group, entity type) scoping row for a Data Access Rule. */
  List<GroupEntity> findAllByGroupIdentifier(ObjectId groupIdentifier);

  Optional<GroupEntity> findByGroupIdentifierAndAccessEntityType(
      ObjectId groupIdentifier, AccessEntityTypeEnum accessEntityType);
}
