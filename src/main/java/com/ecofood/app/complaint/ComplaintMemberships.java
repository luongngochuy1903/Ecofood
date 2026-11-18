package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import java.util.*;

final class ComplaintMemberships {
  private ComplaintMemberships() {}
  static boolean eligible(ComplaintRoutingDirectory directory, ComplaintQueue queue, ComplaintQueueMembership membership, QueueCapability capability) {
    if (membership == null || !membership.active() || !Objects.equals(queue.id(), membership.queueId()) || membership.authority() == null
        || !membership.capabilities().contains(capability) || !directory.isActiveEmployee(membership.userId(), queue.scope().tenantId())) return false;
    if (membership.authority() != Authority.NONE && (queue.purpose() != QueuePurpose.FINAL_AUTHORITY
        || membership.authority() != (queue.scope().boundary() == Boundary.PLATFORM ? Authority.PLATFORM_AUTHORITY : Authority.TENANT_AUTHORITY))) return false;
    return capability == QueueCapability.REPORT ? directory.hasPermission(membership.userId(), queue.scope().tenantId(), Permission.COMPLAINT_CREATE)
        : membership.capabilities().contains(QueueCapability.READ) && directory.hasPermission(membership.userId(), queue.scope().tenantId(), Permission.COMPLAINT_READ)
        && directory.hasPermission(membership.userId(), queue.scope().tenantId(), permission(capability));
  }
  static Permission permission(QueueCapability capability) {
    return switch (capability) {
      case REPORT -> Permission.COMPLAINT_CREATE; case READ -> Permission.COMPLAINT_READ;
      case PROCESS -> Permission.COMPLAINT_PROCESS; case ASSIGN -> Permission.COMPLAINT_ASSIGN; case ESCALATE -> Permission.COMPLAINT_ESCALATE;
    };
  }
}
