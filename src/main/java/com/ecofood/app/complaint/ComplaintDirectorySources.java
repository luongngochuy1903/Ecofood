package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import java.util.*;

/** Backend adapter ports keep Complaint out of Tenant/User ACL private repositories. */
public final class ComplaintDirectorySources {
  private ComplaintDirectorySources() {}
  public interface Tenants { Optional<TenantInfo> find(String id); String platformId(); }
  public interface Employees {
    EmployeeFacts resolve(String userId, String tenantId);
    default boolean invoiceAuthorityRouting() { return false; }
    default Set<String> finalAuthorities(String tenantId) { return Set.of(); }
    default String label(String userId, String tenantId) { return userId; }
  }
  public record EmployeeFacts(boolean active, Set<Permission> permissions) {
    public EmployeeFacts { permissions = permissions == null ? Set.of() : Set.copyOf(permissions); }
  }
}
