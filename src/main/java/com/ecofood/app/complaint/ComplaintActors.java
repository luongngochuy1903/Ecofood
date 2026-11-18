package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.core.security.AuthenticationContext;
import java.util.EnumSet;

/** Current JWT identity plus live Tenant/User ACL checks; no role-level shortcuts. */
final class ComplaintActors {
  private ComplaintActors() {}
  static Actor employee(MongoComplaintRoutingDirectory directory, Permission permission) {
    var claims = AuthenticationContext.getClaims(); var codes = AuthenticationContext.getPermissions();
    if (claims == null || "CUSTOMER".equals(claims.get("identityType")) || codes == null || !codes.contains(permission.name())) throw denied();
    String user = claims.getSubject(), tenant = claims.get("tenantId", String.class); ComplaintValidation.id(user); ComplaintValidation.id(tenant);
    if (!directory.isActiveEmployee(user, tenant) || !directory.hasPermission(user, tenant, permission) || !codes.contains(Permission.COMPLAINT_READ.name())
        || !directory.hasPermission(user, tenant, Permission.COMPLAINT_READ)) throw denied();
    var permissions = EnumSet.noneOf(Permission.class);
    for (var p : Permission.values()) if (codes.contains(p.name()) && directory.hasPermission(user, tenant, p)) permissions.add(p);
    return new Actor(ActorType.EMPLOYEE, user, tenant, permissions);
  }
  private static ForbiddenException denied() { return new ForbiddenException("Employee Complaint access required"); }
}
