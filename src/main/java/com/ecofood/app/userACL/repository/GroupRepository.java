package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.Group;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GroupRepository extends MongoRepository<Group, ObjectId> {

  Optional<Group> findByCode(String code);
}
