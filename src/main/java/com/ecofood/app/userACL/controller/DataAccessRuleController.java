package com.ecofood.app.userACL.controller;

import com.ecofood.core.dto.CreateDataAccessRuleRequest;
import com.ecofood.core.dto.DataAccessRuleDetailResponse;
import com.ecofood.core.dto.DataAccessRuleDirectoryResponse;
import com.ecofood.core.dto.DataAccessRuleResponse;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.UpdateDataAccessRuleRequest;
import com.ecofood.core.service.DataAccessRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data-access-rules")
@RequiredArgsConstructor
public class DataAccessRuleController {

  private final DataAccessRuleService dataAccessRuleService;

  @PostMapping
  public ResponseEntity<DataAccessRuleResponse> create(@Valid @RequestBody CreateDataAccessRuleRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(dataAccessRuleService.create(request));
  }

  /**
   * Backs the "Data Access Rules" list — paginated, searchable, role-scoped
   * listing (see DataAccessRuleService.listDirectory). Authenticated-only:
   * /api/v1/data-access-rules/** isn't in SecurityConfig's permitAll list,
   * so it falls through to the default anyRequest().authenticated().
   */
  @GetMapping("/directory")
  public ResponseEntity<PageResponse<DataAccessRuleDirectoryResponse>> getDirectory(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "updatedAt,desc") String sort,
      @RequestParam(required = false) String search) {
    return ResponseEntity.ok(dataAccessRuleService.listDirectory(page, size, sort, search));
  }

  /** Backs the "Edit Data Access Rule" modal's row-click popup — same concept as GET /api/v1/tenants/{id}. */
  @GetMapping("/{id}")
  public ResponseEntity<DataAccessRuleDetailResponse> getById(@PathVariable String id) {
    return ResponseEntity.ok(dataAccessRuleService.getById(id));
  }

  /** Only the rule's own creator may call this — see DataAccessRuleServiceImpl.update's ownership check. */
  @PutMapping("/{id}")
  public ResponseEntity<DataAccessRuleDetailResponse> update(
      @PathVariable String id, @Valid @RequestBody UpdateDataAccessRuleRequest request) {
    return ResponseEntity.ok(dataAccessRuleService.update(id, request));
  }
}
