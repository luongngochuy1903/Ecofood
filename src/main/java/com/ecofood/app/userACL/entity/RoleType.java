package com.ecofood.app.userACL.entity;

/**
 * Matches DCMP's real Service_SecurityManagement Role.RoleType exactly
 * (constant names and all, confirmed by reading that class directly) —
 * whether a role replicates to related entities (Functional) or stays
 * siloed (Administrative).
 */
public enum RoleType {
  Functional,
  Administrative
}
