package com.ecofood.app.common.client;

import com.ecofood.app.common.service.UserQueryService;
import com.ecofood.app.userACL.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Cross-module entry point to UserQueryService — e.g. the auth module calls this, never userACL directly. */
@Service
@RequiredArgsConstructor
public class CoreUserQueryClient {

  private final UserQueryService userQueryService;

  public UserEntity getById(String userId) {
    return userQueryService.getById(userId);
  }

  public UserEntity getByUsername(String username) {
    return userQueryService.getByUsername(username);
  }
}
