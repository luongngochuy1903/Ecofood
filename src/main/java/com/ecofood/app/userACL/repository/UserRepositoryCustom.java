package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.UserEntity;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Backs the "Users" sub-tab directory — split out from UserRepository for
 * the same reason as TenantRepositoryCustom: the combined scoping+regex-
 * search+paging query can't be expressed as a Spring Data derived query.
 */
public interface UserRepositoryCustom {

  /**
   * unrestricted=true (caller is SYSTEM-level) returns every user, ignoring
   * scopedTenantIds. unrestricted=false returns every user with a
   * TENANT-coded partyRelationships entry pointing at any id in
   * scopedTenantIds. search, when non-blank, additionally requires a
   * case-insensitive regex "contains" match on firstName, lastName, or
   * email (matching any one of the three).
   */
  Page<UserEntity> findDirectory(
      List<ObjectId> scopedTenantIds, boolean unrestricted, String search, Pageable pageable);
}
