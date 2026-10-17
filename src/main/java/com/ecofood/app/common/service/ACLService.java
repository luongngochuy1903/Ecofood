package com.ecofood.app.common.service;

import com.ecofood.app.common.entity.RoleLevel;
import com.ecofood.app.common.security.AuthenticationContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Matches DCMP's real Service_Core ACLService's hasXLevelRole() design
 * exactly (confirmed by reading that class directly): a pure in-memory check
 * against the roleGroups claim already present in the verified JWT — no DB
 * call, no cache needed, since AuthenticationContext re-parses the token
 * fresh on every access anyway.
 *
 * getMaxLevel() mirrors DCMP's real priority-order scan, adapted to this
 * project's own 5 levels (see RoleLevel javadoc for why they differ from
 * DCMP's real RoleGroupType set and what priority order was chosen).
 *
 * Deliberately NOT ported yet (per explicit instruction — "just these
 * first"): DCMP's real getMaxLevelByContext (needs a remote
 * SecMgt-equivalent client + ContextType concept), validateOwner/
 * createOwnerFilterForCurrentSession (needs OwnerInfo + insurer/customer/
 * contract domain entities this project has none of), and
 * validateOperationPermissions' any/party/own scope-tier hierarchy.
 */
@Service
public class ACLService {

  public boolean hasSystemLevelRole() {
    return isBelongToGroup(RoleLevel.SYSTEM);
  }

  public boolean hasEnterpriseLevelRole() {
    return isBelongToGroup(RoleLevel.ENTERPRISE);
  }

  public boolean hasMasterTenantLevelRole() {
    return isBelongToGroup(RoleLevel.MASTER_TENANT);
  }

  public boolean hasTenantLevelRole() {
    return isBelongToGroup(RoleLevel.TENANT);
  }

  public boolean hasCustomerLevelRole() {
    return isBelongToGroup(RoleLevel.CUSTOMER);
  }

  /** Highest level the current session holds, checked in priority order (SYSTEM first); NONE if no level matches. */
  public RoleLevel getMaxLevel() {
    if (hasSystemLevelRole()) {
      return RoleLevel.SYSTEM;
    }
    if (hasEnterpriseLevelRole()) {
      return RoleLevel.ENTERPRISE;
    }
    if (hasMasterTenantLevelRole()) {
      return RoleLevel.MASTER_TENANT;
    }
    if (hasTenantLevelRole()) {
      return RoleLevel.TENANT;
    }
    if (hasCustomerLevelRole()) {
      return RoleLevel.CUSTOMER;
    }
    return RoleLevel.NONE;
  }

  private boolean isBelongToGroup(RoleLevel level) {
    List<String> roleGroups = AuthenticationContext.getRoleGroups();
    return roleGroups != null && roleGroups.contains(level.name());
  }
}
