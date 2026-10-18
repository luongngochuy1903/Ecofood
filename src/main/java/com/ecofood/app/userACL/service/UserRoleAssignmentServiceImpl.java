package com.ecofood.app.userACL.service;

import com.ecofood.core.service.UserRoleAssignmentService;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.app.userACL.entity.Role;
import com.ecofood.app.userACL.entity.UserRole;
import com.ecofood.app.userACL.repository.RoleRepository;
import com.ecofood.app.userACL.repository.UserRoleRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Assigns the tenant's first/primary user an admin role at onboarding time,
 * based on the tenant's partnerType — a project-specific rule, not sourced
 * from real DCMP (see PartnerTypeEnum javadoc).
 *
 * No role is ever created here — role codes are hardcoded constants
 * resolved against ALREADY-EXISTING Role documents, which a human creates
 * directly (no seeding/startup automation, per explicit instruction).
 * assignRoleForOnboarding(...) fails fast if the expected Role row is
 * missing rather than silently onboarding a user with no role.
 */
@Service
@RequiredArgsConstructor
public class UserRoleAssignmentServiceImpl implements UserRoleAssignmentService {

  private static final String ROLE_CODE_ENTERPRISE_ADMIN = "ENTERPRISE_ADMIN";
  private static final String ROLE_CODE_TENANT_ADMIN = "TENANT_ADMIN";

  private final RoleRepository roleRepository;
  private final UserRoleRepository userRoleRepository;

  @Override
  public void assignRoleForOnboarding(ObjectId userId, ObjectId tenantId, PartnerTypeEnum partnerType) {
    String roleCode = resolveRoleCode(partnerType);

    Role role =
        roleRepository
            .findByCode(roleCode)
            .orElseThrow(
                () -> new IllegalStateException(
                    "Role not found: " + roleCode + " — this role must be created manually before onboarding"));

    UserRole userRole =
        UserRole.builder()
            .userId(userId)
            .tenantId(tenantId)
            .roles(
                List.of(
                    UserRole.EmbeddedRole.builder()
                        .id(role.getId())
                        .code(role.getCode())
                        .name(role.getName())
                        .roleType(role.getRoleType())
                        .build()))
            .build();

    userRoleRepository.save(userRole);
  }

  /** ENTERPRISE/REGIONAL_ENTERPRISE -> ENTERPRISE_ADMIN; ECOSYSTEM_PARTNER/BRAND -> TENANT_ADMIN. */
  private String resolveRoleCode(PartnerTypeEnum partnerType) {
    return switch (partnerType) {
      case ENTERPRISE, REGIONAL_ENTERPRISE -> ROLE_CODE_ENTERPRISE_ADMIN;
      case ECOSYSTEM_PARTNER, BRAND -> ROLE_CODE_TENANT_ADMIN;
    };
  }
}
