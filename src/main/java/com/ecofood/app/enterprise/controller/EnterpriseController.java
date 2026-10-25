package com.ecofood.app.enterprise.controller;

import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.service.EnterpriseService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists enterprises, optionally filtered by category — e.g.
 * ?enterpriseCategoryCode=ENTERPRISE backs the tenant onboarding form's
 * "related enterprise" picker (shown for a REGIONAL_ENTERPRISE, which
 * attaches to an already-existing master-level ENTERPRISE).
 */
@RestController
@RequestMapping("/api/v1/enterprises")
@RequiredArgsConstructor
public class EnterpriseController {

  private final EnterpriseService enterpriseService;

  @GetMapping
  public ResponseEntity<List<EnterpriseResponse>> listEnterprises(
      @RequestParam(required = false) PartnerTypeEnum enterpriseCategoryCode) {
    return ResponseEntity.ok(enterpriseService.listEnterprises(enterpriseCategoryCode));
  }
}
