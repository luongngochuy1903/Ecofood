package com.ecofood.app.userACL.entity;

/**
 * NOT a DCMP-sourced enum — confirmed by research that real DCMP's role
 * eligibility is driven by tenantCategory/relationship-type, not any
 * partnerType field. This is this project's own business rule: a tenant's
 * partnerType at onboarding decides which admin role its first user gets
 * (see UserRoleAssignmentService).
 */
public enum PartnerTypeEnum {
  ENTERPRISE,
  REGIONAL_ENTERPRISE,
  ECOSYSTEM_PARTNER,
  BRAND
}
