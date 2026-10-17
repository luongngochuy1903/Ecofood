package com.ecofood.app.common.service;

import com.ecofood.app.userACL.entity.UserEntity;

/**
 * Plain same-module-facing interface, implemented by userACL's
 * UserQueryServiceImpl. Returns the entity as-is (repository passthrough) —
 * per instruction, response-DTO conversion doesn't apply to raw repository
 * returns.
 */
public interface UserQueryService {

  UserEntity getById(String userId);

  UserEntity getByUsername(String username);
}
