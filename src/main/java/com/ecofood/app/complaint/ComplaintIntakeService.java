package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.complaint.ComplaintIntakeRequests.*;
import com.ecofood.app.complaint.ComplaintIntakeResponses.*;
import com.ecofood.app.config.*;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.customer.repository.CustomerPurchaseRepository;
import com.ecofood.app.receipt.service.ReceiptService;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.security.AuthenticationContext;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class ComplaintIntakeService {
  private final MongoComplaintRoutingDirectory directory;
  private final RoutingPolicyResolver routing;
  private final ComplaintWorkflowService core;
  private final ComplaintRepository complaints;
  private final ComplaintHistoryService history;
  private final CustomerIdentityService customers;
  private final CustomerPurchaseRepository purchases;
  private final ReceiptService receipts;
  private final GuestComplaintAccessService guestAccess;
  private final ComplaintPublicRateLimiter rates;
  private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");
  private static final Pattern CREDENTIAL = Pattern.compile("(?i)(password|access_token|refresh_token|client_secret|authorization)\\s*[:=]|\\bBearer\\s+[A-Za-z0-9_.-]{12,}");

  public GuestConfirmation guest(String receiptToken, InvoiceIssue request) {
    return directory.inSnapshot(() -> {
      var receipt = receipts.requireValidReceipt(receiptToken); rates.receipt(receipt.access().getId());
      var actor = new Actor(ActorType.GUEST, null, null, Set.of());
      var result = create(Kind.INVOICE, actor, receipt.invoice(), request, receipt.access().getId());
      var access = guestAccess.ensure(result, receipt.access().getId(), request.requestId());
      return new GuestConfirmation(confirm(result), access.trackingUrl(), access.expiresAt());
    });
  }
  public Confirmation customerInvoice(CustomerInvoice request) {
    return directory.inSnapshot(() -> {
      var actor = customer(Permission.COMPLAINT_CREATE); var invoice = ownedInvoice(actor, request.invoiceId());
      return confirm(create(Kind.INVOICE, actor, invoice, request.issue(), null));
    });
  }
  public Confirmation platform(PlatformIssue request) {
    return directory.inSnapshot(() -> {
      var actor = customer(Permission.COMPLAINT_CREATE); String description = technical(request.description(), request.technicalContext());
      return confirm(create(Kind.PLATFORM, actor, null, new InvoiceIssue(request.requestId(), request.categoryCode(), request.subject(), description, null), null));
    });
  }
  public Confirmation internal(InternalIssue request) {
    return directory.inSnapshot(() -> confirm(create(Kind.INTERNAL, employee(Permission.COMPLAINT_CREATE), null,
        new InvoiceIssue(request.requestId(), request.categoryCode(), request.subject(), request.description(), null), null)));
  }
  private CommandResult create(Kind kind, Actor actor, Invoice invoice, InvoiceIssue input, String guestScope) {
    ComplaintCommandIdentity.requestId(input.requestId()); content(input.subject(), input.description());
    var verified = invoice == null ? null : new VerifiedInvoice(invoice.getId(), invoice.getTenantId(), invoice.getDistributionPartnerId());
    var items = itemReferences(invoice, input.invoiceItemId());
    var draft = new SubmitCommand(input.requestId(), input.requestId(), kind, input.categoryCode(), input.subject(), input.description(), actor, verified, items, null, guestScope);
    // Core remains the authority for keys/fingerprints/results. Restore its original decision on retry,
    // so a new catalog version cannot turn an identical request into a different business payload.
    var existing = complaints.findSubmission(ComplaintCommandIdentity.submitKey(draft));
    RoutingDecision decision;
    if (existing.isPresent()) {
      decision = existing.get().getSubmittedRouting();
      if (decision == null && existing.get().getVersion() == 0) {
        var old = existing.get(); decision = new RoutingDecision(old.getRoutingPolicyId(), old.getRoutingPolicyVersion(), old.getCurrentQueueId(), new ProcessingScope(old.getProcessingBoundary(), old.getProcessingTenantId()), old.getReporterOriginQueueId());
      }
      if (decision == null) throw unavailable();
    } else {
      String source = kind == Kind.PLATFORM ? directory.platformTenantId() : kind == Kind.INVOICE ? invoice.getTenantId() : actor.tenantId();
      ComplaintCategories.require(directory.categories(), kind, input.categoryCode(), source);
      decision = resolve(() -> switch (kind) { case INVOICE -> routing.invoice(verified, actor, input.categoryCode()); case PLATFORM -> routing.platform(actor, input.categoryCode()); case INTERNAL -> routing.internal(actor, input.categoryCode()); });
    }
    var command = new SubmitCommand(draft.requestId(), draft.correlationId(), kind, draft.categoryCode(), draft.subject(), draft.description(), actor, verified, items, decision, guestScope);
    try { return core.submit(command); }
    catch (IllegalStateException conflict) {
      // Another replica may have committed the same request using its earlier catalog snapshot.
      // Recheck through Core with the winner's decision; a different client payload still conflicts.
      var winner = complaints.findSubmission(ComplaintCommandIdentity.submitKey(command));
      if (winner.isEmpty() || winner.get().getSubmittedRouting() == null) throw conflict;
      return core.submit(new SubmitCommand(command.requestId(), command.correlationId(), kind, command.categoryCode(), command.subject(), command.description(), actor, verified, items, winner.get().getSubmittedRouting(), guestScope));
    }
  }
  public Options guestOptions(String token) {
    return directory.inSnapshot(() -> options(Kind.INVOICE, new Actor(ActorType.GUEST, null, null, Set.of()), receipts.requireValidReceipt(token).invoice()));
  }
  public Options customerOptions(Kind kind, String invoiceId) {
    return directory.inSnapshot(() -> {
      var actor = customer(Permission.COMPLAINT_CREATE);
      if (kind == Kind.INVOICE) return options(kind, actor, ownedInvoice(actor, invoiceId));
      if (kind != Kind.PLATFORM || invoiceId != null) throw new BadRequestException("Invalid Complaint options scope");
      return options(kind, actor, null);
    });
  }
  public Options internalOptions() { return directory.inSnapshot(() -> options(Kind.INTERNAL, employee(Permission.COMPLAINT_CREATE), null)); }
  private Options options(Kind kind, Actor actor, Invoice invoice) {
    String owner = kind == Kind.PLATFORM ? directory.platformTenantId() : kind == Kind.INVOICE ? invoice.getTenantId() : actor.tenantId();
    var categories = new ArrayList<Category>();
    for (String code : directory.categories().stream().filter(c -> c.active() && c.kind() == kind).map(ComplaintCategory::code).distinct().sorted().toList()) {
      try {
        var category = ComplaintCategories.require(directory.categories(), kind, code, owner);
        switch (kind) {
          case INVOICE -> routing.invoice(new VerifiedInvoice(invoice.getId(), invoice.getTenantId(), invoice.getDistributionPartnerId()), actor, code);
          case PLATFORM -> routing.platform(actor, code);
          case INTERNAL -> routing.internal(actor, code);
        }
        categories.add(new Category(category.code(), category.name()));
      } catch (BadRequestException | ForbiddenException ex) { /* Only configured, currently valid routes are selectable. */ }
    }
    if (categories.isEmpty()) throw unavailable();
    List<Item> items = invoice == null || invoice.getItems() == null ? List.of() : java.util.stream.IntStream.range(0, invoice.getItems().size())
        .mapToObj(i -> new Item("line:" + i, invoice.getItems().get(i).productName())).toList();
    return new Options(categories, items, kind == Kind.INVOICE ? "ACCEPTED_RECORDED_TRANSACTION; PAYMENT_STATUS_UNAVAILABLE" : "AUTHENTICATED_REPORTER");
  }
  public PageResponse<Summary> customerMine(int page, int size, Kind kind, Status status) {
    return customerMine(page, size, kind, status, null, null, null);
  }
  public PageResponse<Summary> customerMine(int page, int size, Kind kind, Status status, String search, java.time.Instant from, java.time.Instant to) {
    return directory.inSnapshot(() -> {
      var actor = customer(Permission.COMPLAINT_READ);
      if (kind == Kind.INTERNAL || page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid Customer Complaint filter");
      var rows = complaints.query(new ReporterScope(actor.type(), actor.userId(), actor.tenantId()),
          new Filter(status == null ? Set.of() : Set.of(status), kind == null ? Set.of(Kind.INVOICE, Kind.PLATFORM) : Set.of(kind), from, to), PageRequest.of(page, size), search);
      return PageResponse.<Summary>builder().content(rows.getContent().stream().map(this::summary).toList()).page(page).size(size).totalElements(rows.getTotalElements()).totalPages(rows.getTotalPages()).build();
    });
  }
  public PageResponse<Summary> employeeMine(int page, int size, Status status) {
    return directory.inSnapshot(() -> mine(employee(Permission.COMPLAINT_READ), page, size, Kind.INTERNAL, status));
  }
  private PageResponse<Summary> mine(Actor actor, int page, int size, Kind kind, Status status) {
    if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid Complaint page");
    var rows = complaints.query(new ReporterScope(actor.type(), actor.userId(), actor.tenantId()),
        new Filter(status == null ? Set.of() : Set.of(status), kind == null ? Set.of() : Set.of(kind), null, null), PageRequest.of(page, size));
    return PageResponse.<Summary>builder().content(rows.getContent().stream().map(this::summary).toList()).page(page).size(size).totalElements(rows.getTotalElements()).totalPages(rows.getTotalPages()).build();
  }
  public Detail customerDetail(String id) { return directory.inSnapshot(() -> detail(id, customer(Permission.COMPLAINT_READ), null)); }
  public Detail customerDetail(String id, int page, int size) { return directory.inSnapshot(() -> detail(id, customer(Permission.COMPLAINT_READ), null, page, size)); }
  public Detail employeeDetail(String id) { return directory.inSnapshot(() -> detail(id, employee(Permission.COMPLAINT_READ), null)); }
  public Detail track(Track request) { return directory.inSnapshot(() -> detail(request.complaintId(), new Actor(ActorType.GUEST, null, null, Set.of()), guestAccess.verify(request.complaintId(), request.accessSecret()))); }
  public Detail track(Track request, int page, int size) { return directory.inSnapshot(() -> detail(request.complaintId(), new Actor(ActorType.GUEST, null, null, Set.of()), guestAccess.verify(request.complaintId(), request.accessSecret()), page, size)); }
  private Detail detail(String id, Actor actor, VerifiedGuestAccess guest) {
    return detail(id, actor, guest, 0, 100);
  }
  private Detail detail(String id, Actor actor, VerifiedGuestAccess guest, int page, int size) {
    QueryScope scope = actor.type() == ActorType.GUEST ? new GuestCaseScope(guest.complaintId()) : new ReporterScope(actor.type(), actor.userId(), actor.tenantId());
    var row = complaints.findById(id, scope).orElseThrow(() -> new IllegalArgumentException("Complaint not found"));
    if (actor.type() == ActorType.EMPLOYEE && row.getKind() != Kind.INTERNAL) throw new IllegalArgumentException("Complaint not found");
    var events = history.reporter(id, actor, guest, page, size);
    return new Detail(summary(row), row.getDescription(), events.getContent(), events.getPage(), events.getTotalPages(), events.getTotalElements());
  }
  private Actor customer(Permission permission) {
    var user = customers.requireSelfServiceAccount(permission.name());
    var claims = AuthenticationContext.getClaims(); var codes = AuthenticationContext.getPermissions(); var permissions = EnumSet.noneOf(Permission.class);
    for (var p : Permission.values()) if (codes.contains(p.name()) && (p == Permission.COMPLAINT_CREATE || p == Permission.COMPLAINT_READ)) permissions.add(p);
    return new Actor(ActorType.CUSTOMER, user.getIdAsString(), claims.get("tenantId", String.class), permissions);
  }
  private Actor employee(Permission permission) {
    var claims = AuthenticationContext.getClaims(); var codes = AuthenticationContext.getPermissions();
    if (claims == null || "CUSTOMER".equals(claims.get("identityType")) || codes == null || !codes.contains(permission.name())) throw new ForbiddenException("Employee Complaint access required");
    String user = claims.getSubject(), tenant = claims.get("tenantId", String.class); ComplaintValidation.id(user); ComplaintValidation.id(tenant);
    if (!directory.isActiveEmployee(user, tenant) || !directory.hasPermission(user, tenant, permission)) throw new ForbiddenException("Employee Complaint access required");
    var permissions = EnumSet.noneOf(Permission.class); for (var p : Permission.values()) if (codes.contains(p.name()) && directory.hasPermission(user, tenant, p)) permissions.add(p);
    return new Actor(ActorType.EMPLOYEE, user, tenant, permissions);
  }
  private Invoice ownedInvoice(Actor actor, String id) {
    ComplaintValidation.id(id);
    return purchases.detail(new CustomerIdentityService.CustomerSession(actor.userId(), actor.tenantId()), id)
        .filter(invoice -> invoice.getStatus() == com.ecofood.core.enums.TransactionStatus.ACCEPTED)
        .orElseThrow(() -> new IllegalArgumentException("Claimed Invoice not found"));
  }
  private List<InvoiceItemReference> itemReferences(Invoice invoice, String id) {
    if (id == null) return List.of();
    if (invoice == null || !id.matches("line:(0|[1-9][0-9]{0,3})")) throw new BadRequestException("Invalid Invoice item identifier");
    int index = Integer.parseInt(id.substring(5));
    if (invoice.getItems() == null || index >= invoice.getItems().size()) throw new BadRequestException("Invoice item is not part of this Invoice");
    return List.of(new InvoiceItemReference(index, invoice.getItems().get(index).productCode()));
  }
  private void content(String subject, String description) {
    ComplaintValidation.text(subject, 200, "Subject"); ComplaintValidation.text(description, 10000, "Description");
    if (subject.codePoints().anyMatch(c -> c < 32 || c == 127) || description.codePoints().anyMatch(c -> c < 32 && c != 9 && c != 10 && c != 13 || c == 127)
        || JWT.matcher(description).find() || JWT.matcher(subject).find() || CREDENTIAL.matcher(description).find() || CREDENTIAL.matcher(subject).find())
      throw new BadRequestException("Remove control characters and credentials from Complaint content");
  }
  private String technical(String description, TechnicalContext context) {
    if (context == null) return description;
    if (context.page() != null && !context.page().matches("/customer/(account|profile|invoices|statistics|notifications|settings|complaints)")) throw new BadRequestException("Technical page must be a supported Customer route without parameters");
    if (context.feature() != null && !context.feature().matches("[A-Za-z0-9_. -]{1,80}") || context.errorCode() != null && !context.errorCode().matches("[A-Z0-9_:-]{1,64}")) throw new BadRequestException("Invalid technical context");
    return description + "\n\nTechnical context: page=" + Objects.toString(context.page(), "") + "; feature=" + Objects.toString(context.feature(), "") + "; errorCode=" + Objects.toString(context.errorCode(), "");
  }
  private RoutingDecision resolve(Supplier<RoutingDecision> action) { try { return action.get(); } catch (BadRequestException ex) { throw unavailable(); } }
  private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Complaint routing is unavailable; contact support"); }
  private Confirmation confirm(CommandResult result) { return new Confirmation(result.complaintId(), result.complaintCode(), result.status(), result.processedAt()); }
  private Summary summary(Complaint row) { return new Summary(row.getIdAsString(), row.getComplaintCode(), row.getKind(), row.getCategoryCode(), row.getSubject(), row.getStatus(), row.getSubmittedAt(), row.getInvoiceId()); }
}
