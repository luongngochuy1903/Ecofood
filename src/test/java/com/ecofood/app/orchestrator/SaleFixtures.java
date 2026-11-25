package com.ecofood.app.orchestrator;

import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.proposition.entity.PropositionEntity;
import java.util.List;
import org.bson.types.ObjectId;

final class SaleFixtures {
  static final String TENANT = "111111111111111111111111",
      PROP = "222222222222222222222222",
      PLAN = "333333333333333333333333",
      OTHER_PLAN = "444444444444444444444444",
      PRODUCT = "555555555555555555555555";

  static PlanSaleAccess.Scope scope(String planId) {
    var p = new PropositionEntity();
    p.setId(new ObjectId(PROP));
    var plan = new Plan();
    plan.setId(new ObjectId(planId));
    return new PlanSaleAccess.Scope(TENANT, "employee", p, plan);
  }

  static Plan plan(String planId, String code, com.ecofood.core.enums.PlanStatusEnum status) {
    var plan = new Plan();
    plan.setId(new ObjectId(planId));
    plan.setCode(code);
    plan.setStatus(status);
    plan.setTenantIds(List.of(TENANT));
    return plan;
  }

  static com.ecofood.core.dto.ProductProcessResponse processNamed(String planId, String name) {
    var definition =
        new com.ecofood.core.dto.ProductDefinitionAttributeResponse(
            "attribute", planId, 0L, name, null, null, null, null, null, null);
    return new com.ecofood.core.dto.ProductProcessResponse(
        planId, planId, 1, 0L, null, null,
        new com.ecofood.core.dto.ProductProcessElementResponse<>("definition", "Definition", List.of(definition)),
        null, null, false, null, null, null, null, null);
  }
}
