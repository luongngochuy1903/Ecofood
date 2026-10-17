package com.ecofood.app.common.service;

import com.ecofood.app.common.dto.AssignTenantRolesRequest;
import com.ecofood.app.common.dto.AssignTenantRolesResponse;
import com.ecofood.app.common.dto.UserCreateRequest;
import com.ecofood.app.common.dto.UserResponse;

/** Plain same-module-facing interface, implemented by userACL's UserServiceImpl. */
public interface UserService {

  UserResponse createUser(UserCreateRequest request);

  /** Grants an already-existing user a role on a tenant — never creates a new UserEntity or touches Keycloak. */
  AssignTenantRolesResponse assignTenantRoles(String userId, AssignTenantRolesRequest request);
}
