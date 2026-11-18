package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.enums.TransactionStatus;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ComplaintValidation {
  private final InvoiceRepository invoices;
  public void submission(SubmitCommand c) { submissionShape(c); invoiceSource(c); }
  public void submissionShape(SubmitCommand c) {
    require(c != null && c.kind() != null && c.actor() != null && c.actor().type() != null, "Complaint kind and authenticated context required");
    ComplaintCommandIdentity.requestId(c.requestId()); correlation(c.correlationId());
    require(c.categoryCode() != null && c.categoryCode().matches("[A-Z][A-Z0-9_]{0,63}"), "Invalid category code");
    text(c.subject(), 200, "Subject"); text(c.description(), 10000, "Description");
    var actor = c.actor();
    if (actor.type() == ActorType.GUEST) require(actor.userId() == null && actor.tenantId() == null && c.kind() == Kind.INVOICE
        && c.guestSourceKey() != null && c.guestSourceKey().matches("[A-Za-z0-9:_.-]{1,128}"), "Invalid Guest submission context");
    else { id(actor.userId()); id(actor.tenantId()); require(c.guestSourceKey() == null, "Unexpected Guest source"); }
    var route = c.routing(); require(route != null && route.processing() != null && route.processing().boundary() != null, "Resolved routing required");
    code(route.policyId()); code(route.queueId()); id(route.processing().tenantId()); require(route.policyVersion() > 0, "Invalid routing version");
    switch (c.kind()) {
      case INVOICE -> {
        require(actor.type() == ActorType.GUEST || actor.type() == ActorType.CUSTOMER, "Invoice reporter must be Guest or Customer");
        require(c.invoice() != null, "Invoice source required"); id(c.invoice().invoiceId()); id(c.invoice().tenantId());
        text(c.invoice().distributionPartnerId(), 128, "Distribution Partner");
        require(route.processing().boundary() == Boundary.TENANT && route.processing().tenantId().equals(c.invoice().tenantId())
            && route.reporterOriginQueueId() == null, "Invoice initial processing scope must match its seller");
        require(c.invoiceItemReferences().size() <= 100, "Too many item references"); Set<Integer> seen = new HashSet<>();
        for (var item : c.invoiceItemReferences()) require(item.lineIndex() >= 0 && seen.add(item.lineIndex())
            && item.productCode() != null && !item.productCode().isBlank(), "Invalid Invoice item reference");
      }
      case PLATFORM -> require(actor.type() == ActorType.CUSTOMER && c.invoice() == null && c.invoiceItemReferences().isEmpty()
          && route.processing().boundary() == Boundary.PLATFORM && route.reporterOriginQueueId() == null, "Invalid Platform source or boundary");
      case INTERNAL -> {
        require(actor.type() == ActorType.EMPLOYEE && c.invoice() == null && c.invoiceItemReferences().isEmpty(), "Invalid Internal reporter/source");
        code(route.reporterOriginQueueId());
      }
    }
  }
  public com.ecofood.app.transaction.entity.Invoice invoiceSource(SubmitCommand c) {
    if (c.kind() != Kind.INVOICE) return null;
    var invoice = invoices.findById(c.invoice().invoiceId()).filter(row -> Objects.equals(row.getTenantId(), c.invoice().tenantId())
        && Objects.equals(row.getDistributionPartnerId(), c.invoice().distributionPartnerId()) && row.getStatus() == TransactionStatus.ACCEPTED)
        .orElseThrow(() -> new BadRequestException("Invoice source unavailable"));
    for (var item : c.invoiceItemReferences()) require(invoice.getItems() != null && item.lineIndex() < invoice.getItems().size()
        && Objects.equals(item.productCode(), invoice.getItems().get(item.lineIndex()).productCode()), "Item reference does not match Invoice");
    return invoice;
  }
  public void workflow(WorkflowCommand c) {
    require(c != null && c.actor() != null && c.actor().type() != null && c.visibility() != null && c.expectedVersion() >= 0, "Invalid workflow command");
    id(c.complaintId()); ComplaintCommandIdentity.requestId(c.requestId()); correlation(c.correlationId());
    text(c.reason(), 2000, "Workflow reason");
    if (c.actor().type() == ActorType.GUEST) require(c.actor().userId() == null && c.actor().tenantId() == null, "Invalid Guest actor");
    else { id(c.actor().userId()); id(c.actor().tenantId()); }
  }
  static String correlation(String value) { return value == null ? null : ComplaintCommandIdentity.requestId(value); }
  static void id(String value) { require(value != null && ObjectId.isValid(value) && new ObjectId(value).toHexString().equals(value), "Invalid canonical identifier"); }
  static void code(String value) { require(value != null && value.matches("[A-Za-z0-9:_.-]{1,128}"), "Invalid routing identifier"); }
  static void text(String value, int max, String label) {
    require(value != null && !value.isBlank() && value.length() <= max && value.indexOf('\0') < 0, label + " is required or too long");
  }
  static void require(boolean condition, String message) { if (!condition) throw new BadRequestException(message); }
}
