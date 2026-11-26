package com.ecofood.app.orchestrator.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.orchestrator.repository.PlanSaleOrchestratorRepository;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.PlanSaleOrchestratorDirectoryResponse;
import com.ecofood.core.dto.PlanSaleOrchestratorRequest;
import com.ecofood.core.dto.PlanSaleOrchestratorResponse;
import com.ecofood.core.dto.SaleConfigurationField;
import com.ecofood.core.service.PlanSaleOrchestratorService;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PlanSaleOrchestratorServiceImpl implements PlanSaleOrchestratorService {
  /** planItems must always describe exactly these three keys — an Additional Info field can never reuse one either. */
  private static final Set<String> PLAN_ITEM_KEYS = Set.of("planId", "quantity", "actualPrice");
  private static final Set<String> ALLOWED_DATA_TYPES = Set.of("STRING", "NUMBER", "BOOLEAN", "DATE");

  private final PlanSaleOrchestratorRepository repository;
  private final PlanSaleAccess access;
  private final CoreProductProcessClient productProcesses;

  /**
   * Any actor who can see this Proposition at all can view every Sale
   * Orchestrator on it, not only ones created under their own Tenant —
   * the only scope that matters for viewing is "same Proposition." Editing
   * stays narrower (see update()/load()).
   */
  @Override
  public PlanSaleOrchestratorResponse get(String propositionId, String id) {
    var scope = access.currentForProposition(propositionId);
    var row = loadForView(scope, id);
    return response(row, scope);
  }

  @Override
  public PlanSaleOrchestratorResponse create(String propositionId, PlanSaleOrchestratorRequest request) {
    var scope = access.currentForProposition(propositionId);
    var planIds = requirePlanIds(scope, request, scope.tenantId(), null);
    var row = new PlanSaleOrchestrator();
    row.setTenantId(scope.tenantId());
    row.setPropositionId(propositionId);
    row.setName(request.name().trim());
    row.setDescription(normalizeDescription(request.description()));
    row.setPlanIds(planIds);
    row.setPlanItems(requirePlanItems(request));
    row.setAdditionalInfoFields(requireAdditionalInfoFields(request));
    row.setCreateCustomer(Boolean.TRUE.equals(request.createCustomer()));
    row.setInvoiceHolder(requireInvoiceHolderFields(request));
    row.setCreatedAt(Instant.now());
    row.setCreatedBy(scope.actor());
    row.setUpdatedAt(row.getCreatedAt());
    row.setUpdatedBy(scope.actor());
    return response(save(row), scope);
  }

  @Override
  public PlanSaleOrchestratorResponse update(String propositionId, String id, PlanSaleOrchestratorRequest request) {
    var scope = access.currentForProposition(propositionId);
    var row = load(scope, id);
    revision(row, request.expectedRevision());
    var planIds = requirePlanIds(scope, request, row.getTenantId(), row.getId());
    row.setName(request.name().trim());
    row.setDescription(normalizeDescription(request.description()));
    row.setPlanIds(planIds);
    row.setPlanItems(requirePlanItems(request));
    row.setAdditionalInfoFields(requireAdditionalInfoFields(request));
    row.setCreateCustomer(Boolean.TRUE.equals(request.createCustomer()));
    row.setInvoiceHolder(requireInvoiceHolderFields(request));
    row.setUpdatedAt(Instant.now());
    row.setUpdatedBy(scope.actor());
    return response(save(row), scope);
  }

  @Override
  public PageResponse<PlanSaleOrchestratorDirectoryResponse> directory(
      String propositionId, int page, int size, String sort, String search) {
    var scope = access.currentForProposition(propositionId);
    var planNames = new HashMap<String, String>();
    for (var plan : scope.plans()) planNames.put(plan.getIdAsString(), planName(plan));
    var needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
    // Same "same Proposition is the only scope" rule as get() — every
    // participant sees every Sale Orchestrator on this Proposition.
    var rows = repository.findByPropositionId(propositionId);
    var all =
        rows.stream()
            .map(row -> directoryRow(row, planNames))
            .filter(
                row ->
                    needle.isBlank()
                        || row.name().toLowerCase(Locale.ROOT).contains(needle)
                        || (row.description() != null
                                && row.description().toLowerCase(Locale.ROOT).contains(needle))
                        || row.planNames().stream()
                                .anyMatch(name -> name != null && name.toLowerCase(Locale.ROOT).contains(needle)))
            .sorted(directorySort(sort))
            .toList();
    int pageSize = Math.min(Math.max(size, 1), 100);
    int from = Math.min(page * pageSize, all.size()), to = Math.min(from + pageSize, all.size());
    return PageResponse.<PlanSaleOrchestratorDirectoryResponse>builder()
        .content(all.subList(from, to))
        .page(page)
        .size(pageSize)
        .totalElements(all.size())
        .totalPages((int) Math.ceil(all.size() / (double) pageSize))
        .build();
  }

  private PlanSaleOrchestratorDirectoryResponse directoryRow(
      PlanSaleOrchestrator row, Map<String, String> planNames) {
    return new PlanSaleOrchestratorDirectoryResponse(
        row.getId(),
        row.getName(),
        row.getDescription(),
        List.copyOf(row.getPlanIds()),
        row.getPlanIds().stream().map(id -> planNames.getOrDefault(id, id)).toList(),
        row.getUpdatedAt());
  }

  private Comparator<PlanSaleOrchestratorDirectoryResponse> directorySort(String sort) {
    var parts = (sort == null || sort.isBlank() ? "name,asc" : sort).split(",", 2);
    String field = parts[0].trim();
    boolean descending = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc");
    Comparator<PlanSaleOrchestratorDirectoryResponse> comparator =
        switch (field) {
          case "updatedAt" -> Comparator.comparing(
              PlanSaleOrchestratorDirectoryResponse::updatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
          default -> Comparator.comparing(
              PlanSaleOrchestratorDirectoryResponse::name, Comparator.nullsLast(Comparator.naturalOrder()));
        };
    return descending ? comparator.reversed() : comparator;
  }

  private PlanSaleOrchestratorResponse response(PlanSaleOrchestrator row, PlanSaleAccess.PropositionScope scope) {
    var byId = new HashMap<String, Plan>();
    for (var plan : scope.plans()) byId.put(plan.getIdAsString(), plan);
    var plans =
        row.getPlanIds().stream()
            .map(
                planId -> {
                  var plan = byId.get(planId);
                  return new PlanSaleOrchestratorResponse.PlanRef(
                      planId,
                      plan == null ? null : plan.getCode(),
                      plan == null ? null : planName(plan),
                      plan == null ? null : plan.getStatus());
                })
            .toList();
    return new PlanSaleOrchestratorResponse(
        row.getId(),
        row.getRevision(),
        row.getPropositionId(),
        row.getName(),
        row.getDescription(),
        plans,
        List.copyOf(row.getPlanItems()),
        List.copyOf(row.getAdditionalInfoFields()),
        List.copyOf(row.getInvoiceHolder()),
        row.getCreatedAt(),
        row.getUpdatedAt(),
        row.getCreatedBy(),
        row.getUpdatedBy(),
        scope.enterprise() || row.getTenantId().equals(scope.tenantId()),
        row.isCreateCustomer());
  }

  /**
   * planItems always describes exactly planId/quantity/actualPrice (only
   * dataType/shortName/description/required are configurable per field) —
   * left null/empty, defaults to planId/quantity required, actualPrice
   * optional.
   */
  private List<SaleConfigurationField> requirePlanItems(PlanSaleOrchestratorRequest request) {
    var fields = request.planItems() == null || request.planItems().isEmpty()
        ? defaultPlanItems() : request.planItems();
    var keys = fields.stream().map(SaleConfigurationField::key).collect(java.util.stream.Collectors.toSet());
    if (!keys.equals(PLAN_ITEM_KEYS))
      throw new BadRequestException("Item Plan fields must be exactly planId, quantity and actualPrice");
    validateDataTypes(fields);
    return List.copyOf(fields);
  }

  /**
   * invoiceHolder is free-form, like additionalInfoFields, but scoped to the
   * buyer's own info carried once per invoice — left null/empty, defaults to
   * firstName/lastName/email/phoneNumber/address, all optional.
   */
  private List<SaleConfigurationField> requireInvoiceHolderFields(PlanSaleOrchestratorRequest request) {
    var fields = request.invoiceHolder() == null || request.invoiceHolder().isEmpty()
        ? defaultInvoiceHolder() : request.invoiceHolder();
    requireUniqueKeys(fields, Set.of());
    validateDataTypes(fields);
    return List.copyOf(Boolean.TRUE.equals(request.createCustomer()) ? withLockedExternalId(fields) : fields);
  }

  /**
   * When createCustomer is enabled, invoiceHolder always carries exactly one
   * externalId field, forced required — this is the key submit-transaction
   * matches against Customer.externalIds, so its presence/required-ness is
   * never left to the request (any externalId field the request submitted
   * is replaced, not merely validated).
   */
  private List<SaleConfigurationField> withLockedExternalId(List<SaleConfigurationField> fields) {
    var combined = new ArrayList<SaleConfigurationField>(fields.size() + 1);
    for (var f : fields) if (!"externalId".equals(f.key())) combined.add(f);
    combined.add(new SaleConfigurationField("externalId", "STRING", "External Id",
        "Distribution partner's own id for this Customer — matched against Customer.externalIds to auto-link the Invoice.", true));
    return combined;
  }

  /**
   * Validates the request's own Additional Info fields: non-blank, unique
   * keys, a recognized dataType, and never reusing one of the planItems
   * keys (planId/quantity/actualPrice), which are not configurable here.
   */
  private List<SaleConfigurationField> requireAdditionalInfoFields(PlanSaleOrchestratorRequest request) {
    var fields = request.additionalInfoFields() == null ? List.<SaleConfigurationField>of() : request.additionalInfoFields();
    requireUniqueKeys(fields, PLAN_ITEM_KEYS);
    validateDataTypes(fields);
    return List.copyOf(fields);
  }

  private void requireUniqueKeys(List<SaleConfigurationField> fields, Set<String> reserved) {
    var seen = new HashSet<String>();
    for (var field : fields) {
      if (reserved.contains(field.key()))
        throw new BadRequestException("\"" + field.key() + "\" is a fixed item field and cannot be redefined");
      if (!seen.add(field.key()))
        throw new BadRequestException("Duplicate field key: " + field.key());
    }
  }

  private void validateDataTypes(List<SaleConfigurationField> fields) {
    for (var field : fields)
      if (!ALLOWED_DATA_TYPES.contains(field.dataType()))
        throw new BadRequestException("Unsupported data type: " + field.dataType());
  }

  private static List<SaleConfigurationField> defaultPlanItems() {
    return List.of(
        new SaleConfigurationField("planId", "STRING", "Plan Id", "The Plan the customer is buying", true),
        new SaleConfigurationField("quantity", "NUMBER", "Quantity", "How many of the Plan were bought", true),
        new SaleConfigurationField(
            "actualPrice",
            "NUMBER",
            "Actual Price",
            "Only needed when your own storefront (not the Plan's own defined price) sets what it sold for",
            false));
  }

  private static List<SaleConfigurationField> defaultInvoiceHolder() {
    return List.of(
        new SaleConfigurationField("firstName", "STRING", "First Name", "", false),
        new SaleConfigurationField("lastName", "STRING", "Last Name", "", false),
        new SaleConfigurationField("email", "STRING", "Email", "", false),
        new SaleConfigurationField("phoneNumber", "STRING", "Phone Number", "", false),
        new SaleConfigurationField("address", "STRING", "Address", "", false));
  }

  /**
   * Validates the request's own Plans are all visible to this actor and none
   * is already claimed by another Orchestrator under the same owning
   * Tenant — `ownerTenantId` is the row's own stored tenantId on update
   * (never the current actor's own tenantId, which for an Enterprise actor
   * editing another Tenant's Orchestrator would check the wrong bucket), or
   * the actor's own tenantId on create (a new Orchestrator is always
   * created under its creator's own Tenant identity).
   */
  private List<String> requirePlanIds(
      PlanSaleAccess.PropositionScope scope,
      PlanSaleOrchestratorRequest request,
      String ownerTenantId,
      String selfId) {
    if (request == null || request.planIds() == null || request.planIds().isEmpty())
      throw new BadRequestException("At least one Plan is required");
    if (request.name() == null || request.name().isBlank())
      throw new BadRequestException("Name is required");
    var visible = scope.plans().stream().map(Plan::getIdAsString).collect(java.util.stream.Collectors.toSet());
    var planIds = List.copyOf(new LinkedHashSet<>(request.planIds()));
    for (var planId : planIds)
      if (!visible.contains(planId))
        throw new BadRequestException("Plan is not visible to this Tenant and Proposition: " + planId);
    for (var planId : planIds) {
      var claimedBy = repository.findByTenantIdAndPlanIdsContaining(ownerTenantId, planId);
      if (claimedBy.isPresent() && !claimedBy.get().getId().equals(selfId))
        throw new BadRequestException(
            "Plan already belongs to another Sale Orchestrator: " + planId);
    }
    return planIds;
  }

  /**
   * Edit-scoped lookup, used only by update(): a Tenant actor may only load
   * (and so only edit) its own Orchestrators; an Enterprise actor owns the
   * whole Proposition, so it can load — and edit — any Tenant's.
   */
  private PlanSaleOrchestrator load(PlanSaleAccess.PropositionScope scope, String id) {
    var found =
        scope.enterprise()
            ? repository.findByIdAndPropositionId(id, scope.proposition().getIdAsString())
            : repository.findByIdAndTenantIdAndPropositionId(
                id, scope.tenantId(), scope.proposition().getIdAsString());
    return found.orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale Orchestrator not found"));
  }

  /**
   * View-scoped lookup, used only by get(): any actor who can see this
   * Proposition at all (already enforced by access.currentForProposition())
   * can view any Sale Orchestrator on it, regardless of which Tenant
   * created it.
   */
  private PlanSaleOrchestrator loadForView(PlanSaleAccess.PropositionScope scope, String id) {
    return repository
        .findByIdAndPropositionId(id, scope.proposition().getIdAsString())
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale Orchestrator not found"));
  }

  private void revision(PlanSaleOrchestrator row, Long expected) {
    if (expected == null || !expected.equals(row.getRevision()))
      throw conflict("Sale Orchestrator changed; reload before retrying");
  }

  private PlanSaleOrchestrator save(PlanSaleOrchestrator row) {
    try {
      return repository.save(row);
    } catch (OptimisticLockingFailureException ex) {
      throw conflict("Sale Orchestrator changed; reload before retrying");
    } catch (DuplicateKeyException ex) {
      throw conflict("A Plan in this request already belongs to another Sale Orchestrator");
    }
  }

  /**
   * The Plan's own name lives on its cloned product process, not on the
   * embedded Plan itself — legacy data whose content was never created (or
   * was removed) shouldn't take this whole response down with it.
   */
  private String planName(Plan plan) {
    try {
      var content = productProcesses.get(plan.getIdAsString());
      if (content.definition() == null
          || content.definition().processElementAttribute() == null
          || content.definition().processElementAttribute().isEmpty()) return null;
      return content.definition().processElementAttribute().get(0).productName();
    } catch (ResponseStatusException ex) {
      return null;
    }
  }

  private String normalizeDescription(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
