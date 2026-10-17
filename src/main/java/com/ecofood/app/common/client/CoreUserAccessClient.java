package com.ecofood.app.common.client;

import com.ecofood.app.common.service.UserAccessService;
import com.ecofood.app.common.service.UserAccessService.UserAccessClaims;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/** Cross-module entry point to UserAccessService — e.g. the auth module calls this, never userACL directly. */
@Service
@RequiredArgsConstructor
public class CoreUserAccessClient {

  private final UserAccessService userAccessService;

  public UserAccessClaims resolveAccess(ObjectId userId, ObjectId tenantId) {
    return userAccessService.resolveAccess(userId, tenantId);
  }
}
