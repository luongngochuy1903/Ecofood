package com.ecofood.app.userACL.service;

import com.ecofood.app.common.service.UserQueryService;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Read-only user lookups for OTHER modules — auth must never import
 * userACL.repository.UserRepository directly, only CoreUserQueryClient
 * (common.client), which delegates to this bean via the UserQueryService
 * interface.
 */
@Service
@RequiredArgsConstructor
public class UserQueryServiceImpl implements UserQueryService {

  private final UserRepository userRepository;

  @Override
  public UserEntity getById(String userId) {
    return userRepository
        .findById(new ObjectId(userId))
        .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
  }

  @Override
  public UserEntity getByUsername(String username) {
    return userRepository
        .findByUsername(username)
        .orElseThrow(() -> new IllegalArgumentException("No local user found for username=" + username));
  }
}
