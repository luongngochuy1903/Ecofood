package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import java.util.*;

/**
 * Trusted read port. Task 2 must supply repository/Core client adapters with current membership,
 * permissions and effective resource Data Scope. No default-allow production implementation exists.
 */
public interface ComplaintRoutingDirectory {
  Optional<TenantInfo> tenant(String tenantId);
  String platformTenantId();
  Optional<ComplaintQueue> queue(String queueId);
  List<ComplaintQueueMembership> memberships(String queueId);
  List<ComplaintCategory> categories();
  List<ComplaintRoutingPolicy> policies();
  default Optional<RoutingDecision> invoiceAuthorityRoute(VerifiedInvoice invoice) { return Optional.empty(); }
  default boolean isInvoiceAuthorityCase(ComplaintCase complaint) { return false; }
  boolean isActiveEmployee(String userId, String tenantId);
  boolean hasPermission(String userId, String tenantId, Permission permission);
  boolean inDataScope(Actor actor, ComplaintCase complaint, Permission operation);
}
