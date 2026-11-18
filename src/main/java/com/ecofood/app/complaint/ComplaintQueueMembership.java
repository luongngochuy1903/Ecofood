package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import java.util.Set;

/** Membership grants queue capabilities, not permissions or tenant membership by itself. */
public record ComplaintQueueMembership(String id, String queueId, String userId, boolean active,
    Set<QueueCapability> capabilities, Authority authority) {
  public ComplaintQueueMembership { capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities); }
}
