package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import java.time.Instant;
import java.util.*;

/** Published versions must be stored immutably; active-version uniqueness must be enforced by Task 2. */
public record ComplaintRoutingPolicy(String id, int version, String ownerTenantId, Kind kind, Set<String> categoryCodes,
    Set<String> queueIds, List<Edge> edges, Set<String> reporterOriginQueueIds, List<PartnerBinding> partnerBindings,
    String platformInitialQueueId, Publication publication, boolean active, Instant publishedAt, String publishedBy) {
  public ComplaintRoutingPolicy {
    categoryCodes = categoryCodes == null ? Set.of() : Set.copyOf(categoryCodes);
    queueIds = queueIds == null ? Set.of() : Set.copyOf(queueIds);
    edges = edges == null ? List.of() : List.copyOf(edges);
    reporterOriginQueueIds = reporterOriginQueueIds == null ? Set.of() : Set.copyOf(reporterOriginQueueIds);
    partnerBindings = partnerBindings == null ? List.of() : List.copyOf(partnerBindings);
  }
  public record Edge(String fromQueueId, String toQueueId) {}
  /** DP identifiers are opaque strings, never interpreted as tenant identifiers. */
  public record PartnerBinding(String distributionPartnerId, String queueId) {}
}
