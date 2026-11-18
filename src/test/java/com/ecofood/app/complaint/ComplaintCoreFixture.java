package com.ecofood.app.complaint;

import static org.mockito.Mockito.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ComplaintCoreFixture implements ComplaintRoutingDirectory {
  static final String SELLER = "000000000000000000000001", PORTAL = "000000000000000000000002", PLATFORM = "000000000000000000000003";
  static final String CUSTOMER = "000000000000000000000011", OTHER = "000000000000000000000012", EMPLOYEE = "000000000000000000000013";
  static final String HANDLER = "000000000000000000000014", AUTHORITY = "000000000000000000000015", PLATFORM_HANDLER = "000000000000000000000016";
  static final String INVOICE = "000000000000000000000021", CASE = "000000000000000000000031";
  final MutableClock clock = new MutableClock();
  final InvoiceRepository invoices = mock(InvoiceRepository.class);
  final Invoice invoice = new Invoice();
  final Set<String> denied = new HashSet<>();
  final ComplaintAccessPolicy access = new ComplaintAccessPolicy(this, clock);
  final ComplaintAuthorization authorization;
  ComplaintCoreFixture() {
    invoice.setId(INVOICE); invoice.setTenantId(SELLER); invoice.setDistributionPartnerId("SHOP-OPAQUE"); invoice.setPosId("POS-A");
    invoice.setInvoiceNumber("INV-ORIGINAL"); invoice.setStatus(TransactionStatus.ACCEPTED); invoice.setCurrency("VND"); invoice.setTotalAmount(BigDecimal.TEN);
    invoice.setTransactionTime(clock.instant()); invoice.setItems(List.of(new InvoiceItemDto("P1", "Milk", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN)));
    when(invoices.findById(INVOICE)).thenReturn(Optional.of(invoice));
    var beans = new StaticListableBeanFactory(); beans.addBean("access", access); authorization = new ComplaintAuthorization(beans.getBeanProvider(ComplaintAccessPolicy.class));
  }
  Actor customer() { return new Actor(ActorType.CUSTOMER, CUSTOMER, PORTAL, Set.of(Permission.COMPLAINT_CREATE, Permission.COMPLAINT_READ)); }
  Actor guest() { return new Actor(ActorType.GUEST, null, null, Set.of()); }
  Actor employee() { return actor(EMPLOYEE, SELLER); }
  Actor handler() { return actor(HANDLER, SELLER); }
  Actor actor(String id, String tenant) { return new Actor(ActorType.EMPLOYEE, id, tenant, EnumSet.allOf(Permission.class)); }
  SubmitCommand submit(Kind kind, String request) { return submit(kind, request, kind == Kind.INTERNAL ? employee() : customer()); }
  SubmitCommand submit(Kind kind, String request, Actor actor) {
    return new SubmitCommand(request, "trace-original", kind, kind == Kind.INVOICE ? "PRODUCT_ISSUE" : kind == Kind.PLATFORM ? "LOGIN" : "STAFF_ISSUE",
        "Subject", "Description", actor, kind == Kind.INVOICE ? new VerifiedInvoice(INVOICE, SELLER, "SHOP-OPAQUE") : null,
        kind == Kind.INVOICE ? List.of(new InvoiceItemReference(0, "P1")) : List.of(),
        new RoutingDecision("policy-" + kind, 1, kind == Kind.PLATFORM ? "platform-support" : "review",
            new ProcessingScope(kind == Kind.PLATFORM ? Boundary.PLATFORM : Boundary.TENANT, kind == Kind.PLATFORM ? PLATFORM : SELLER), kind == Kind.INTERNAL ? "origin" : null),
        actor.type() == ActorType.GUEST ? "verified-receipt-access-id" : null);
  }
  SubmitCommand content(SubmitCommand c, String subject, String description) {
    return new SubmitCommand(c.requestId(), c.correlationId(), c.kind(), c.categoryCode(), subject, description, c.actor(), c.invoice(), c.invoiceItemReferences(), c.routing(), c.guestSourceKey());
  }
  WorkflowCommand command(CommandResult prior, String request, Actor actor, Visibility visibility) {
    return new WorkflowCommand(prior.complaintId(), prior.version(), request, "trace-flow", actor, null, "Reason for workflow", visibility);
  }
  Complaint row() {
    var row = new Complaint(); row.setId(new org.bson.types.ObjectId(CASE)); row.setComplaintCode("CMP-TEST"); row.setVersion(0L); row.setKind(Kind.INVOICE);
    row.setReporterType(ActorType.CUSTOMER); row.setReporterUserId(CUSTOMER); row.setReporterHomeTenantId(PORTAL); row.setStatus(Status.SUBMITTED);
    row.setProcessingBoundary(Boundary.TENANT); row.setProcessingTenantId(SELLER); row.setCurrentQueueId("review"); return row;
  }
  ComplaintEvent event(Complaint row, Visibility visibility) {
    String id = row.getIdAsString() + ":1";
    return new ComplaintEvent(id, row.getIdAsString(), 1, EventType.COMPLAINT_SUBMITTED, row.getReporterType(), row.getReporterUserId(), null, Status.SUBMITTED,
        null, row.getCurrentQueueId(), "Initial event", visibility, clock.instant(), "trace", "command-key", "fingerprint",
        new CommandResult(row.getIdAsString(), row.getComplaintCode(), Status.SUBMITTED, 0, id, 1, clock.instant()));
  }
  public Optional<TenantInfo> tenant(String id) {
    return Set.of(SELLER, PORTAL, PLATFORM).contains(id) ? Optional.of(new TenantInfo(id, TenantStructure.STANDALONE, null, PLATFORM.equals(id), true)) : Optional.empty();
  }
  public String platformTenantId() { return PLATFORM; }
  public Optional<ComplaintQueue> queue(String id) {
    return switch (id) {
      case "review" -> Optional.of(new ComplaintQueue(id, "Review", new ProcessingScope(Boundary.TENANT, SELLER), QueuePurpose.PROCESSING, true));
      case "final" -> Optional.of(new ComplaintQueue(id, "Final", new ProcessingScope(Boundary.TENANT, SELLER), QueuePurpose.FINAL_AUTHORITY, true));
      case "platform-support" -> Optional.of(new ComplaintQueue(id, "Platform Support", new ProcessingScope(Boundary.PLATFORM, PLATFORM), QueuePurpose.FINAL_AUTHORITY, true));
      default -> Optional.empty();
    };
  }
  public List<ComplaintQueueMembership> memberships(String queue) {
    String user = queue.equals("review") ? HANDLER : queue.equals("final") ? AUTHORITY : PLATFORM_HANDLER;
    return List.of(new ComplaintQueueMembership("membership", queue, user, true, EnumSet.allOf(QueueCapability.class),
        queue.equals("review") ? Authority.NONE : queue.equals("final") ? Authority.TENANT_AUTHORITY : Authority.PLATFORM_AUTHORITY));
  }
  public List<ComplaintCategory> categories() { return ComplaintCategories.suggestedDefaults(); }
  public List<ComplaintRoutingPolicy> policies() { return List.of(); }
  public boolean isActiveEmployee(String user, String tenant) {
    return tenant.equals(SELLER) && Set.of(EMPLOYEE, HANDLER, AUTHORITY).contains(user) || tenant.equals(PLATFORM) && PLATFORM_HANDLER.equals(user);
  }
  public boolean hasPermission(String user, String tenant, Permission permission) { return isActiveEmployee(user, tenant); }
  public boolean inDataScope(Actor actor, ComplaintCase complaint, Permission permission) { return !denied.contains(actor.userId()); }
  static class MutableClock extends Clock {
    Instant value = Instant.parse("2026-11-16T00:00:00Z"); public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return value; }
    void advance(long seconds) { value = value.plusSeconds(seconds); }
  }
}
