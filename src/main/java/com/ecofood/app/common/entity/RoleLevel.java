package com.ecofood.app.common.entity;

/**
 * Not a DCMP-sourced enum verbatim — DCMP's real RoleGroupType/RoleLevelEnum
 * values (DCMP/MASTER_TENANT/TENANT/OWNER/INSURER/API_CONSUMER) don't map
 * 1:1 onto this project's model (no insurer/customer/contract domain).
 * These 5 levels and their priority order are this project's own choice,
 * explicitly specified.
 *
 * The mechanism matching the level IS DCMP's real one: ACLService checks
 * AuthenticationContext.getRoleGroups() (the JWT's roleGroups claim) for
 * exact string membership of a level's name() — e.g. isBelongToGroup
 * checking "MASTER_TENANT". For that check to ever succeed, a human must
 * seed Group/GroupRole rows with `name` set to exactly these 5 strings —
 * that's a data convention (matching DCMP's own), not logic this enum or
 * ACLService performs.
 */
public enum RoleLevel {
  NONE(0),
  CUSTOMER(1),
  TENANT(2),
  MASTER_TENANT(3),
  ENTERPRISE(4),
  SYSTEM(5);

  private final int priority;

  RoleLevel(int priority) {
    this.priority = priority;
  }

  public int getPriority() {
    return priority;
  }
}
