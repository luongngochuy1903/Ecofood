package com.ecofood.app.common.client;

import com.ecofood.app.common.dto.UserCreateRequest;
import com.ecofood.app.common.dto.UserResponse;
import com.ecofood.app.common.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Cross-module entry point to UserService — any OTHER module (e.g. tenant)
 * must call this, never userACL's UserServiceImpl directly. A thin delegate,
 * not something UserServiceImpl itself implements, so the client layer
 * stays decoupled from the implementation's own interface.
 */
@Service
@RequiredArgsConstructor
public class CoreUserClient {

  private final UserService userService;

  public UserResponse createUser(UserCreateRequest request) {
    return userService.createUser(request);
  }
}
