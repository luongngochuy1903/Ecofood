package com.ecofood.app.userACL.service;

import com.ecofood.app.complaint.ComplaintContracts.Permission;
import com.ecofood.app.complaint.ComplaintDirectorySources;
import com.ecofood.app.complaint.ComplaintDirectorySources.EmployeeFacts;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.app.userACL.repository.UserRoleRepository;
import com.ecofood.core.client.CoreUserAccessClient;
import com.ecofood.core.enums.RelationshipTypeEnum;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ComplaintEmployeeLookupAdapter implements ComplaintDirectorySources.Employees {
  private final UserRepository users;
  private final CoreUserAccessClient access;
  private final UserRoleRepository assignments;
  private static final Set<String> ADMIN_ROLES = Set.of("ECO_ADMIN", "ENTERPRISE_ADMIN", "MASTER_ADMIN", "TENANT_ADMIN");
  public boolean invoiceAuthorityRouting() { return true; }
  /** Same Admin authority convention as onboarding/management. Never rank by RoleLevel or permission count. */
  public Set<String> finalAuthorities(String tenantId) {
    if (tenantId == null || !ObjectId.isValid(tenantId)) return Set.of();
    var result = new HashSet<String>();
    for (var assignment : assignments.findAllByTenantId(new ObjectId(tenantId))) {
      if (Boolean.TRUE.equals(assignment.getDeleted()) || assignment.getUserId() == null || !new ObjectId(tenantId).equals(assignment.getTenantId())
          || assignment.getRoles() == null || assignment.getRoles().stream().noneMatch(r -> r.getCode() != null && ADMIN_ROLES.contains(r.getCode()))) continue;
      String user = assignment.getUserId().toHexString();
      if (resolve(user, tenantId).active() && access.resolveAccess(assignment.getUserId(), new ObjectId(tenantId)).roles().stream().anyMatch(ADMIN_ROLES::contains)) result.add(user);
    }
    return Set.copyOf(result);
  }
  public String label(String userId, String tenantId) {
    if (!resolve(userId, tenantId).active()) return userId;
    return users.findById(new ObjectId(userId)).map(u -> (Objects.toString(u.getFirstName(), "") + " " + Objects.toString(u.getLastName(), "")).strip())
        .filter(name -> !name.isBlank()).orElse(userId);
  }
  public EmployeeFacts resolve(String userId, String tenantId) {
    if (userId == null || tenantId == null || !ObjectId.isValid(userId) || !ObjectId.isValid(tenantId)) return new EmployeeFacts(false, Set.of());
    var user = users.findById(new ObjectId(userId)).orElse(null);
    if (user == null || Boolean.TRUE.equals(user.getDeleted()) || user.getCustomerIdentityIssuer() != null || user.getUserCategory() == null
        || !"PARTNER".equals(user.getUserCategory().getUserCategoryCode()) || user.getExternalLoginId() == null || user.getExternalLoginId().isBlank()
        || user.getPartyRelationships() == null || user.getPartyRelationships().stream().noneMatch(r -> new ObjectId(tenantId).equals(r.getToPartyId())
            && RelationshipTypeEnum.TENANT.getCode().equals(r.getRelationshipTypeCode()))) return new EmployeeFacts(false, Set.of());
    var codes = access.resolveAccess(user.getId(), new ObjectId(tenantId)).permissions(); var permissions = EnumSet.noneOf(Permission.class);
    for (var permission : Permission.values()) if (codes.contains(permission.name())) permissions.add(permission);
    return new EmployeeFacts(true, permissions);
  }
}
