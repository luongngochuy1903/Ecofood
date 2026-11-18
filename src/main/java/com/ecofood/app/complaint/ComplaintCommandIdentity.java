package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.config.BadRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class ComplaintCommandIdentity {
  private ComplaintCommandIdentity() {}
  static String requestId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9:_.-]{1,128}")) throw new BadRequestException("Invalid command request identifier"); return value;
  }
  static String submitKey(SubmitCommand c) {
    Actor actor = c.actor();
    String source = c.kind() == Kind.INVOICE ? c.invoice().tenantId() + ":" + c.invoice().invoiceId() : c.kind() == Kind.INTERNAL ? actor.tenantId() : "PLATFORM";
    return hash("submit:v1", c.kind(), actor.type(), actor.userId(), actor.tenantId(), actor.type() == ActorType.GUEST ? c.guestSourceKey() : null, source, requestId(c.requestId()));
  }
  static String workflowKey(WorkflowCommand c) {
    return hash("workflow:v1", c.complaintId(), c.actor().type(), c.actor().userId(), c.actor().tenantId(), requestId(c.requestId()));
  }
  static String submitFingerprint(SubmitCommand c) {
    var values = new ArrayList<Object>(Arrays.asList("submit-payload:v1", c.kind(), c.categoryCode(), c.subject().strip(), c.description().strip(),
        c.invoice() == null ? null : c.invoice().invoiceId(), c.invoice() == null ? null : c.invoice().tenantId(),
        c.invoice() == null ? null : c.invoice().distributionPartnerId(), c.routing().policyId(), c.routing().policyVersion(), c.routing().queueId(),
        c.routing().processing().boundary(), c.routing().processing().tenantId(), c.routing().reporterOriginQueueId()));
    c.invoiceItemReferences().stream().sorted(Comparator.comparingInt(InvoiceItemReference::lineIndex)).forEach(item -> { values.add(item.lineIndex()); values.add(item.productCode()); });
    return hash(values.toArray());
  }
  static String workflowFingerprint(Operation operation, WorkflowCommand c) {
    return hash("workflow-payload:v1", operation, c.expectedVersion(), c.reason() == null ? null : c.reason().strip(), c.visibility());
  }
  static String assignmentFingerprint(Operation operation, WorkflowCommand c, String targetUser) {
    return hash("processing-payload:v1", workflowFingerprint(operation, c), targetUser);
  }
  static String hash(Object... values) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (Object value : values) {
        if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); continue; }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("Command hashing unavailable"); }
  }
}
