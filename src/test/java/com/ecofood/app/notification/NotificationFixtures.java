package com.ecofood.app.notification;

import com.ecofood.app.notification.config.NotificationSettings;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRule;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.dto.NotificationDtos;
import com.ecofood.core.enums.TransactionStatus;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;

final class NotificationFixtures {
  static final String TENANT = "123456789012345678901234", OTHER = "223456789012345678901234", USER = "323456789012345678901234";
  static Submission event() { return new Submission("event", "INVOICE_PAID", Instant.now(), TENANT, "INVOICE", "invoice", "prop", "plan", "orch", 3); }
  static Invoice invoice() {
    var i = new Invoice(); i.setId("invoice"); i.setTenantId(TENANT); i.setSubmissionId("event");
    i.setStatus(TransactionStatus.ACCEPTED); i.setPropositionId("prop"); i.setPlanId("plan");
    i.setOrchestratorId("orch"); i.setOrchestratorVersion(3); i.setPostWorkflowResults(Map.of("SEND_EMAIL_NOTIFICATION", "PENDING"));
    return i;
  }
  static NotificationSettings settings() { return new NotificationSettings("sender@example.org", true, 3, TENANT + "=approved@example.org"); }
  static NotificationRule rule() {
    var r = new NotificationRule(); r.setId("rule"); r.setTenantId(TENANT); r.setEventType("INVOICE_PAID"); r.setEnabled(true); r.setRevision(2);
    r.setSubject("Invoice {invoiceNumber}"); r.setContent("Recorded {invoiceNumber}");
    r.setRecipients(new NotificationDtos.Recipients(List.of(USER), List.of(), List.of(), false, List.of())); return r;
  }
  static UserEntity user() {
    var u = new UserEntity(); u.setId(new ObjectId(USER)); u.setEmail("customer@example.org");
    u.setPartyRelationships(List.of(PartyToPartyRelationship.builder().relationshipTypeCode("TENANT").toPartyId(new ObjectId(TENANT)).build())); return u;
  }
  static Notification delivery() {
    var n = new Notification(); n.setId("delivery"); n.setTenantId(TENANT); n.setSubmissionId("event"); n.setRuleId("rule"); n.setType("INVOICE_PAID");
    n.setSendFrom("sender@example.org"); n.setSendTo("customer@example.org"); n.setSubject("Invoice"); n.setContent("Recorded");
    n.setNextAttemptAt(Instant.now().minusSeconds(1)); return n;
  }
}
