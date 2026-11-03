package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.GroupRole;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GroupRoleRepository extends MongoRepository<GroupRole, ObjectId> {

  List<GroupRole> findByGroupId(ObjectId groupId);

  /** Every GroupRole doc that currently lists this role — used to find/reassign a role's current group. */
  List<GroupRole> findByRolesContaining(ObjectId roleId);
}
