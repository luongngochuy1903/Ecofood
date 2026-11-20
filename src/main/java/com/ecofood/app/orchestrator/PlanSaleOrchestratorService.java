package com.ecofood.app.orchestrator;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.orchestrator.PlanSaleOrchestrator.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PlanSaleOrchestratorService {
  private final PlanSaleOrchestratorRepository repository;
  private final PlanSaleAccess access;
  private final SaleSchemaValidator schemas;

  public record Save(Long expectedRevision, Configuration configuration) {}

  public record Revision(Long expectedRevision) {}

  public record Validation(boolean valid, List<String> errors) {}

  public Optional<PlanSaleOrchestrator> get(String proposition, String plan) {
    var scope = access.current(proposition, plan);
    return repository.findByTenantIdAndPropositionIdAndPlanId(
        scope.tenantId(), scope.proposition().getIdAsString(), scope.plan().getIdAsString());
  }

  public PlanSaleOrchestrator create(String proposition, String plan, Save request) {
    var scope = access.current(proposition, plan);
    basic(request == null ? null : request.configuration());
    if (repository
        .findByTenantIdAndPropositionIdAndPlanId(
            scope.tenantId(), scope.proposition().getIdAsString(), scope.plan().getIdAsString())
        .isPresent())
      throw conflict(
          "This Plan already has a Sale Orchestrator; edit its Draft or create a new version");
    var row = new PlanSaleOrchestrator();
    row.setTenantId(scope.tenantId());
    row.setPropositionId(scope.proposition().getIdAsString());
    row.setPlanId(scope.plan().getIdAsString());
    row.setCreatedAt(Instant.now());
    row.setCreatedBy(scope.actor());
    row.getVersions()
        .add(
            new ConfigVersion(
                1,
                Status.DRAFT,
                request.configuration(),
                row.getCreatedAt(),
                scope.actor(),
                row.getCreatedAt(),
                scope.actor(),
                null,
                null));
    stamp(row, "CREATE_DRAFT", 1, scope.actor());
    return save(row);
  }

  public PlanSaleOrchestrator edit(String proposition, String plan, Save request) {
    var scope = access.current(proposition, plan);
    var row = load(scope);
    revision(row, request == null ? null : request.expectedRevision());
    basic(request.configuration());
    var old = draft(row);
    replace(
        row,
        new ConfigVersion(
            old.version(),
            Status.DRAFT,
            request.configuration(),
            old.createdAt(),
            old.createdBy(),
            Instant.now(),
            scope.actor(),
            null,
            null));
    stamp(row, "EDIT_DRAFT", old.version(), scope.actor());
    return save(row);
  }

  public PlanSaleOrchestrator version(String proposition, String plan, Revision request) {
    var scope = access.current(proposition, plan);
    var row = load(scope);
    revision(row, request == null ? null : request.expectedRevision());
    if (row.getVersions().stream().anyMatch(v -> v.status() == Status.DRAFT))
      throw conflict("A Draft version already exists");
    var current = active(row);
    int number = row.getVersions().stream().mapToInt(ConfigVersion::version).max().orElse(0) + 1;
    row.getVersions()
        .add(
            new ConfigVersion(
                number,
                Status.DRAFT,
                current.configuration(),
                Instant.now(),
                scope.actor(),
                Instant.now(),
                scope.actor(),
                null,
                null));
    stamp(row, "CREATE_VERSION", number, scope.actor());
    return save(row);
  }

  public Validation validate(String proposition, String plan, Revision request) {
    var scope = access.current(proposition, plan);
    var row = load(scope);
    revision(row, request == null ? null : request.expectedRevision());
    return validate(scope, draft(row).configuration());
  }

  public PlanSaleOrchestrator activate(String proposition, String plan, Revision request) {
    var scope = access.current(proposition, plan);
    var row = load(scope);
    revision(row, request == null ? null : request.expectedRevision());
    var next = draft(row);
    var checked = validate(scope, next.configuration());
    if (!checked.valid()) throw new BadRequestException(String.join("; ", checked.errors()));
    for (var old : new ArrayList<>(row.getVersions()))
      if (old.status() == Status.ACTIVE)
        replace(
            row,
            new ConfigVersion(
                old.version(),
                Status.ARCHIVED,
                old.configuration(),
                old.createdAt(),
                old.createdBy(),
                old.updatedAt(),
                old.updatedBy(),
                old.activatedAt(),
                old.activatedBy()));
    replace(
        row,
        new ConfigVersion(
            next.version(),
            Status.ACTIVE,
            next.configuration(),
            next.createdAt(),
            next.createdBy(),
            next.updatedAt(),
            next.updatedBy(),
            Instant.now(),
            scope.actor()));
    row.setActiveVersion(next.version());
    stamp(row, "ACTIVATE", next.version(), scope.actor());
    return save(row);
  }

  private Validation validate(PlanSaleAccess.Scope scope, Configuration c) {
    var errors = new ArrayList<>(schemas.validate(c == null ? null : c.payloadSchema()));
    try {
      basic(c);
      access.active(scope);
      String currency = access.currency(scope);
      if (currency == null || !currency.matches("[A-Z]{3}"))
        errors.add("Plan must have a valid currency");
    } catch (BadRequestException | ResponseStatusException ex) {
      errors.add(ex instanceof ResponseStatusException e ? e.getReason() : ex.getMessage());
    }
    return new Validation(errors.isEmpty(), errors);
  }

  private void basic(Configuration c) {
    if (c == null
        || blank(c.name())
        || c.name().length() > 200
        || c.description() != null && c.description().length() > 2000
        || c.maxItems() < 1
        || c.maxItems() > 1000
        || c.postWorkflows() == null
        || c.postWorkflows().stream().anyMatch(Objects::isNull))
      throw new BadRequestException(
          "Name, maxItems (1–1000) and supported post-workflow list are required");
  }

  private PlanSaleOrchestrator load(PlanSaleAccess.Scope scope) {
    return repository
        .findByTenantIdAndPropositionIdAndPlanId(
            scope.tenantId(), scope.proposition().getIdAsString(), scope.plan().getIdAsString())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Sale Orchestrator has not been configured for this Plan"));
  }

  private void revision(PlanSaleOrchestrator row, Long expected) {
    if (expected == null || !expected.equals(row.getRevision()))
      throw conflict("Configuration changed; reload before retrying");
  }

  private PlanSaleOrchestrator save(PlanSaleOrchestrator row) {
    try {
      return repository.save(row);
    } catch (OptimisticLockingFailureException ex) {
      throw conflict("Configuration changed; reload before retrying");
    } catch (DuplicateKeyException ex) {
      throw conflict(
          "This Tenant and Plan already have a Sale Orchestrator; reload before retrying");
    }
  }

  private void stamp(PlanSaleOrchestrator row, String action, int version, String actor) {
    row.setUpdatedAt(Instant.now());
    row.setUpdatedBy(actor);
    row.getAudit().add(new Audit(action, version, row.getUpdatedAt(), actor));
  }

  static ConfigVersion active(PlanSaleOrchestrator row) {
    return row.getVersions().stream()
        .filter(
            v -> v.status() == Status.ACTIVE && Objects.equals(v.version(), row.getActiveVersion()))
        .findFirst()
        .orElseThrow(() -> conflict("No Active Sale configuration"));
  }

  private ConfigVersion draft(PlanSaleOrchestrator row) {
    return row.getVersions().stream()
        .filter(v -> v.status() == Status.DRAFT)
        .findFirst()
        .orElseThrow(() -> conflict("Create a new Draft version before editing"));
  }

  private void replace(PlanSaleOrchestrator row, ConfigVersion v) {
    row.getVersions().replaceAll(old -> old.version() == v.version() ? v : old);
  }

  private boolean blank(String s) {
    return s == null || s.isBlank() || !s.equals(s.strip());
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
