package com.ecofood.app.notification.integration;

import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.enums.TransactionStatus;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;

/** Legacy adapter retained for compatibility tests; intentionally excluded from Spring event sources. */
@RequiredArgsConstructor
public class InvoiceNotificationSource implements NotificationEventSource {
  private final MongoTemplate mongo;
  public String event() { return Submission.INVOICE_PAID; }
  public Context resolve(Submission event) {
    // Batch acknowledgements have one seller-only delivery; do not run legacy Invoice rules again.
    if (Submission.INVOICE_PAID.equals(event.event()) && "INVOICE_BATCH".equals(event.sourceType()))
      return new Context(null,false,Map.of());
    if (!Submission.INVOICE_PAID.equals(event.event()) || !"INVOICE".equals(event.sourceType())) throw new IllegalStateException("INVALID_EVENT_SOURCE");
    Invoice invoice = mongo.findOne(Query.query(Criteria.where("_id").is(event.sourceId()).and("tenantId").is(event.tenantId())), Invoice.class);
    if (invoice == null || invoice.getStatus() != TransactionStatus.ACCEPTED || !"PAID".equals(invoice.getPaymentStatus())
        || !Objects.equals(invoice.getSubmissionId(), event.id()) || !Objects.equals(invoice.getPropositionId(), event.propositionId())
        || !Objects.equals(invoice.getPlanId(), event.planId()) || !Objects.equals(invoice.getOrchestratorId(), event.orchestratorId())
        || invoice.getOrchestratorVersion() != event.orchestratorVersion()) throw new IllegalStateException("INVALID_INVOICE_SCOPE");
    Map<String, String> values = new HashMap<>();
    values.put("submissionId", event.id()); values.put("eventType", event.event()); values.put("tenantId", event.tenantId());
    values.put("invoiceNumber", invoice.getInvoiceNumber()); values.put("propositionId", invoice.getPropositionId()); values.put("planId", invoice.getPlanId());
    values.put("currency", invoice.getCurrency());
    if (invoice.getTotalAmount() != null) values.put("totalAmount", invoice.getTotalAmount().toPlainString());
    if (invoice.getReceivedAt() != null) values.put("receivedAt", invoice.getReceivedAt().toString());
    boolean enabled = invoice.getPostWorkflowResults() != null && invoice.getPostWorkflowResults().containsKey("SEND_EMAIL_NOTIFICATION");
    return new Context(invoice, enabled, values);
  }
}
