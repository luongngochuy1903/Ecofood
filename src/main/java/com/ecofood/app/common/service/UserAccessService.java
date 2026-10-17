package com.ecofood.app.common.service;

import java.util.List;
import org.bson.types.ObjectId;

/** Plain same-module-facing interface, implemented by userACL's UserAccessServiceImpl. */
public interface UserAccessService {

  /**
   * Role codes, flattened/deduped permission codes, and roleGroups across all
   * of a user's roles in one tenant. roleGroups matches DCMP's real
   * computation exactly: for each GroupRole whose roles list overlaps the
   * user's UserRole role ids, emit that GroupRole's (denormalized) name —
   * group membership is entirely transitive through roles, never a direct
   * user-to-group link.
   */
  record UserAccessClaims(List<String> roles, List<String> permissions, List<String> roleGroups) {}

  UserAccessClaims resolveAccess(ObjectId userId, ObjectId tenantId);
}
