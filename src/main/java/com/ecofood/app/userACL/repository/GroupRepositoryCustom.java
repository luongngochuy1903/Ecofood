package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.Group;
import com.ecofood.core.enums.GroupType;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GroupRepositoryCustom {

  /** Backs the Data Access Rules directory — see DataAccessRuleServiceImpl.listDirectory. */
  Page<Group> findDirectory(
      GroupType groupType, List<ObjectId> scopedTenantIds, boolean unrestricted, String search, Pageable pageable);
}
