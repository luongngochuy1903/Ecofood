package com.ecofood.app.orchestrator;

import com.ecofood.app.orchestrator.PlanSaleOrchestrator.*;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.core.dto.SalePayloadSchema;
import com.ecofood.core.dto.SalePayloadSchema.*;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;

final class SaleFixtures {
  static final String TENANT = "111111111111111111111111",
      PROP = "222222222222222222222222",
      PLAN = "333333333333333333333333",
      OTHER_PLAN = "444444444444444444444444";

  static Field field(String name, Type type, boolean required) {
    return new Field(name, type, required, null, null, null, null, null, null, null);
  }

  static SalePayloadSchema schema(Field... extra) {
    var fields =
        new ArrayList<>(
            List.of(
                field("invoiceNumber", Type.STRING, true),
                field("transactionTime", Type.DATE, true),
                field("currency", Type.STRING, true),
                field("totalAmount", Type.NUMBER, true),
                new Field(
                    "items",
                    Type.ARRAY,
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new Field(
                        null,
                        Type.OBJECT,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(
                            field("productCode", Type.STRING, true),
                            field("productName", Type.STRING, true),
                            field("quantity", Type.NUMBER, true),
                            field("unitPrice", Type.NUMBER, true),
                            field("amount", Type.NUMBER, true)),
                        null))));
    fields.addAll(List.of(extra));
    return new SalePayloadSchema(fields);
  }

  static Configuration config() {
    return new Configuration("Plan sales", "", 100, schema(), Set.of());
  }

  static PlanSaleAccess.Scope scope(String planId) {
    var p = new PropositionEntity();
    p.setId(new ObjectId(PROP));
    var plan = new Plan();
    plan.setId(new ObjectId(planId));
    return new PlanSaleAccess.Scope(TENANT, "employee", p, plan);
  }

  static Map<String, Object> payload() {
    return new LinkedHashMap<>(
        Map.of(
            "invoiceNumber",
            "INV-1",
            "transactionTime",
            Instant.now().minusSeconds(60).toString(),
            "currency",
            "VND",
            "totalAmount",
            10,
            "items",
            List.of(
                Map.of(
                    "productCode",
                    "P1",
                    "productName",
                    "Milk",
                    "quantity",
                    1,
                    "unitPrice",
                    10,
                    "amount",
                    10))));
  }
}
