package com.ecofood.app.orchestrator.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.orchestrator.repository.PlanSaleOrchestratorRepository;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.orchestrator.validation.SaleSchemaValidator;
import com.ecofood.core.dto.PlanSaleOrchestratorDtos;
import com.ecofood.core.enums.PlanSaleOrchestratorStatusEnum;
import com.ecofood.core.service.PlanSaleOrchestratorService;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PlanSaleOrchestratorServiceImpl implements PlanSaleOrchestratorService {
  private final PlanSaleOrchestratorRepository repository;
  private final PlanSaleAccess access;
  private final SaleSchemaValidator schemas;

  @Override
  public Optional<PlanSaleOrchestrator> get(String proposition, String plan) {
    var scope = access.current(proposition, plan);
    return repository.findByTenantIdAndPropositionIdAndPlanId(
        scope.tenantId(), scope.proposition().getIdAsString(), scope.plan().getIdAsString());
  }

  @Override
  public PlanSaleOrchestrator create(String proposition, String plan, PlanSaleOrchestratorDtos.Save request) {
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
            new PlanSaleOrchestratorDtos.ConfigVersion(
                1,
                PlanSaleOrchestratorStatusEnum.DRAFT,
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

  @Override
  public PlanSaleOrchestrator edit(String proposition, String plan, PlanSaleOrchestratorDtos.Save request) {
    var scope = access.current(proposition, plan);
    var row = load(scope);
    revision(row, request == null ? null : request.expectedRevision());
    basic(request.configuration());
    var old = draft(row);
    replace(
        row,
        new PlanSaleOrchestratorDtos.ConfigVersion(
            old.version(),
            PlanSaleOrchestratorStatusEnum.DRAFT,
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

  @Override
  public PlanSaleOrchestrator version(String proposition, String plan, PlanSaleOrchestratorDtos.Revision request) {
    var scope = access.current(proposition, plan);
    var row = load(scope);
    revision(row, request == null ? null : request.expectedRevision());
    if (row.getVersions().stream().anyMatch(v -> v.status() == PlanSaleOrchestratorStatusEnum.DRAFT))
      throw conflict("A Draft version already exists");
    var current = active(row);
    int number = row.getVersions().stream().mapToInt(PlanSaleOrchestratorDtos.ConfigVersion::version).max().orElse(0) + 1;
    row.getVersions()
        .add(
            new PlanSaleOrchestratorDtos.ConfigVersion(
                number,
                PlanSaleOrchestratorStatusEnum.DRAFT,
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

  @Override
  public PlanSaleOrchestratorDtos.Validation validate(String proposition, String plan, PlanSaleOrchestratorDtos.Revision request) {
    var scope = access.current(proposition, plan);
    var row = load(scope);
    revision(row, request == null ? null : request.expectedRevision());
    return validate(scope, draft(row).configuration());
  }

  @Override
  public PlanSaleOrchestrator activate(String proposition, String plan, PlanSaleOrchestratorDtos.Revision request) {
    var scope = access.current(proposition, plan);
    var row = load(scope);
    revision(row, request == null ? null : request.expectedRevision());
    var next = draft(row);
    var checked = validate(scope, next.configuration());
    if (!checked.valid()) throw new BadRequestException(String.join("; ", checked.errors()));
    for (var old : new ArrayList<>(row.getVersions()))
      if (old.status() == PlanSaleOrchestratorStatusEnum.ACTIVE)
        replace(
            row,
            new PlanSaleOrchestratorDtos.ConfigVersion(
                old.version(),
                PlanSaleOrchestratorStatusEnum.ARCHIVED,
                old.configuration(),
                old.createdAt(),
                old.createdBy(),
                old.updatedAt(),
                old.updatedBy(),
                old.activatedAt(),
                old.activatedBy()));
    replace(
        row,
        new PlanSaleOrchestratorDtos.ConfigVersion(
            next.version(),
            PlanSaleOrchestratorStatusEnum.ACTIVE,
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

  private PlanSaleOrchestratorDtos.Validation validate(PlanSaleAccess.Scope scope, PlanSaleOrchestratorDtos.Configuration c) {
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
    return new PlanSaleOrchestratorDtos.Validation(errors.isEmpty(), errors);
  }

  private void basic(PlanSaleOrchestratorDtos.Configuration c) {
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
      throw conflict("PlanSaleOrchestratorDtos.Configuration changed; reload before retrying");
  }

  private PlanSaleOrchestrator save(PlanSaleOrchestrator row) {
    try {
      return repository.save(row);
    } catch (OptimisticLockingFailureException ex) {
      throw conflict("PlanSaleOrchestratorDtos.Configuration changed; reload before retrying");
    } catch (DuplicateKeyException ex) {
      throw conflict(
          "This Tenant and Plan already have a Sale Orchestrator; reload before retrying");
    }
  }

  private void stamp(PlanSaleOrchestrator row, String action, int version, String actor) {
    row.setUpdatedAt(Instant.now());
    row.setUpdatedBy(actor);
    row.getAudit().add(new PlanSaleOrchestratorDtos.Audit(action, version, row.getUpdatedAt(), actor));
  }

  static PlanSaleOrchestratorDtos.ConfigVersion active(PlanSaleOrchestrator row) {
    return row.getVersions().stream()
        .filter(
            v -> v.status() == PlanSaleOrchestratorStatusEnum.ACTIVE && Objects.equals(v.version(), row.getActiveVersion()))
        .findFirst()
        .orElseThrow(() -> conflict("No Active Sale configuration"));
  }

  private PlanSaleOrchestratorDtos.ConfigVersion draft(PlanSaleOrchestrator row) {
    return row.getVersions().stream()
        .filter(v -> v.status() == PlanSaleOrchestratorStatusEnum.DRAFT)
        .findFirst()
        .orElseThrow(() -> conflict("Create a new Draft version before editing"));
  }

  private void replace(PlanSaleOrchestrator row, PlanSaleOrchestratorDtos.ConfigVersion v) {
    row.getVersions().replaceAll(old -> old.version() == v.version() ? v : old);
  }

  private boolean blank(String s) {
    return s == null || s.isBlank() || !s.equals(s.strip());
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
