package com.ecofood.app.proposition.controller;

import com.ecofood.app.config.Audited;
import com.ecofood.core.enums.AuditAction;

import com.ecofood.core.dto.*;
import com.ecofood.core.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/propositions")
public class PropositionController {
    private final PropositionService service;
    private final PropositionQueryService query;

    @GetMapping
    public Page<PropositionResponse> list(
            @RequestParam(required = false) String search, Pageable page) {
        return query.list(search, page);
    }

    @GetMapping("/options")
    public PropositionOptionsResponse options() {
        return query.options();
    }

    @GetMapping("/approved-processes")
    public Page<ApprovedProductProcessReference> sources(
            @RequestParam(required = false) String search, Pageable page) {
        return query.approvedProcesses(search, page);
    }

    @GetMapping("/settings")
    public PropositionCurrencySettingsResponse settings() {
        return query.currencySettings();
    }

    @Audited(AuditAction.UPDATE)
    @PutMapping("/settings")
    public PropositionCurrencySettingsResponse settings(
            @Valid @RequestBody PropositionCurrencySettingsRequest r) {
        return service.updateCurrencySettings(r);
    }

    @GetMapping("/{id}")
    public PropositionResponse get(@PathVariable String id) {
        return query.getById(id);
    }

    @Audited(AuditAction.CREATE)
    @PostMapping
    public ResponseEntity<PropositionResponse> create(
            @Valid @RequestBody PropositionCreateRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(r));
    }

    @Audited(AuditAction.UPDATE)
    @PutMapping("/{id}")
    public PropositionResponse update(
            @PathVariable String id, @Valid @RequestBody PropositionUpdateRequest r) {
        return service.update(id, r);
    }

    @Audited(AuditAction.CREATE)
    @PostMapping("/{id}/plans")
    public ResponseEntity<PropositionResponse> addPlan(
            @PathVariable String id, @Valid @RequestBody PlanRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addPlan(id, r));
    }

    @GetMapping("/{id}/plans/directory")
    public PageResponse<PlanResponse> planDirectory(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort,
            @RequestParam(required = false) String search) {
        return query.planDirectory(id, page, size, sort, search);
    }

    @GetMapping("/{id}/plans/{planId}")
    public PlanResponse plan(@PathVariable String id, @PathVariable String planId) {
        return query.getPlan(id, planId);
    }

    @Audited(AuditAction.UPDATE)
    @PutMapping("/{id}/plans/{planId}")
    public PropositionResponse plan(
            @PathVariable String id,
            @PathVariable String planId,
            @Valid @RequestBody PlanRequest r) {
        return service.updatePlan(id, planId, r);
    }

    @Audited(AuditAction.DELETE)
    @DeleteMapping("/{id}/plans/{planId}")
    public PropositionResponse remove(
            @PathVariable String id,
            @PathVariable String planId,
            @Valid @RequestBody RevisionRequest r) {
        return service.removePlan(id, planId, r);
    }

    @Audited(AuditAction.UPDATE)
    @PostMapping("/{id}/plans/{planId}/approve")
    public PropositionResponse approve(
            @PathVariable String id,
            @PathVariable String planId,
            @Valid @RequestBody RevisionRequest r) {
        return service.approvePlan(id, planId, r);
    }

    @PostMapping("/{id}/plans/{planId}/reject")
    public PropositionResponse reject(
            @PathVariable String id,
            @PathVariable String planId,
            @Valid @RequestBody PlanDecisionRequest r) {
        return service.rejectPlan(id, planId, r);
    }

    @Audited(AuditAction.UPDATE)
    @PostMapping("/{id}/plans/{planId}/submit")
    public PropositionResponse submitPlan(
            @PathVariable String id,
            @PathVariable String planId,
            @Valid @RequestBody RevisionRequest r) {
        return service.submitPlan(id, planId, r);
    }

    @Audited(AuditAction.CREATE)
    @PostMapping("/{id}/plans/{planId}/versions")
    public ResponseEntity<PropositionResponse> version(
            @PathVariable String id,
            @PathVariable String planId,
            @Valid @RequestBody RevisionRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.versionPlan(id, planId, r));
    }
}
