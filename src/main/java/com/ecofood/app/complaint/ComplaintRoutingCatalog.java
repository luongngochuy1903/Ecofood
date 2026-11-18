package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("complaint_routing_catalog")
public record ComplaintRoutingCatalog(@Id String id, long configurationVersion, String approvedBy, String contentHash, Instant importedAt, Configuration configuration) {
  public record Configuration(long version, String approvedBy, List<ComplaintCategory> categories, List<ComplaintQueue> queues,
      List<ComplaintQueueMembership> memberships, List<ComplaintRoutingPolicy> policies, List<ScopeGrant> grants) {
    public Configuration { categories = List.copyOf(categories); queues = List.copyOf(queues); memberships = List.copyOf(memberships); policies = List.copyOf(policies); grants = List.copyOf(grants); }
  }
  /** Explicit Complaint resource scope. Legacy Dashboard/Product FULL_ACCESS does not grant Complaint access. */
  public record ScopeGrant(String userId, String queueId, Set<Kind> kinds, Set<String> sourceTenantIds,
      boolean platformSource, boolean allInvoicesWithinSource, Set<String> invoiceIds) {
    public ScopeGrant { kinds = Set.copyOf(kinds); sourceTenantIds = Set.copyOf(sourceTenantIds); invoiceIds = Set.copyOf(invoiceIds); }
  }
}
