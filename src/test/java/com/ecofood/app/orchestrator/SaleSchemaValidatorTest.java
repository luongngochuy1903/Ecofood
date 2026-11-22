package com.ecofood.app.orchestrator;

import static com.ecofood.app.orchestrator.SaleFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.orchestrator.validation.SaleSchemaValidator;
import com.ecofood.core.dto.SalePayloadSchema;
import com.ecofood.core.dto.SalePayloadSchema.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class SaleSchemaValidatorTest {
  private final SaleSchemaValidator validator = new SaleSchemaValidator();

  @Test
  void keepsAllRequiredUc015FieldsIncludingInvoiceItems() {
    assertTrue(validator.validate(schema()).isEmpty());
    assertFalse(
        validator
            .validate(new SalePayloadSchema(List.of(field("invoiceNumber", Type.STRING, false))))
            .isEmpty());
    var changed = new ArrayList<>(schema().fields());
    changed.set(
        4,
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
            new Field(null, Type.OBJECT, false, null, null, null, null, null, List.of(), null)));
    assertTrue(
        validator.validate(new SalePayloadSchema(changed)).stream()
            .anyMatch(e -> e.contains("productName")));
  }

  @Test
  void rejectsDuplicatesUnsupportedRulesAndIdentitySchema() {
    assertFalse(validator.validate(schema(field("currency", Type.STRING, true))).isEmpty());
    assertFalse(
        validator
            .validate(
                schema(
                    new Field(
                        "email",
                        Type.STRING,
                        false,
                        null,
                        BigDecimal.ZERO,
                        null,
                        "SCRIPT",
                        null,
                        null,
                        null)))
            .isEmpty());
    assertFalse(validator.validate(schema(field("tenantId", Type.STRING, false))).isEmpty());
    assertFalse(
        validator
            .validate(
                schema(
                    new Field(null, Type.STRING, false, null, null, null, null, null, null, null)))
            .isEmpty());
  }

  @Test
  void appliesTypedDefaultsAndNumericRanges() {
    var defaults =
        schema(
            new Field(
                "count",
                Type.NUMBER,
                false,
                5,
                BigDecimal.ZERO,
                BigDecimal.TEN,
                null,
                null,
                null,
                null));
    assertEquals(5, validator.apply(defaults, payload()).get("count"));
    var invalid = payload();
    invalid.put("count", 11);
    assertThrows(BadRequestException.class, () -> validator.apply(defaults, invalid));
    assertFalse(
        validator
            .validate(
                schema(
                    new Field(
                        "count", Type.NUMBER, false, "5", null, null, null, null, null, null)))
            .isEmpty());
    assertFalse(
        validator
            .validate(
                schema(
                    new Field(
                        "count",
                        Type.NUMBER,
                        false,
                        null,
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        null,
                        null,
                        null,
                        null)))
            .isEmpty());
  }

  @Test
  void validatesEmailDateAndRequiredFields() {
    var schema =
        schema(
            new Field(
                "customerEmail", Type.STRING, false, null, null, null, "EMAIL", null, null, null));
    var email = payload();
    email.put("customerEmail", "invalid");
    assertThrows(BadRequestException.class, () -> validator.apply(schema, email));
    var date = payload();
    date.put("transactionTime", "today");
    assertThrows(BadRequestException.class, () -> validator.apply(schema, date));
    var missing = payload();
    missing.remove("currency");
    assertThrows(BadRequestException.class, () -> validator.apply(schema, missing));
  }

  @Test
  void validatesNestedObjectsArraysAndUnknownFields() {
    var nested = payload();
    nested.put("items", List.of(Map.of("productCode", "P1")));
    assertThrows(BadRequestException.class, () -> validator.apply(schema(), nested));
    assertFalse(
        validator
            .validate(
                schema(
                    new Field(
                        "broken", Type.ARRAY, false, null, null, null, null, null, null, null)))
            .isEmpty());
    var unknown = payload();
    unknown.put("script", "execute()");
    assertThrows(BadRequestException.class, () -> validator.apply(schema(), unknown));
    var identity = payload();
    identity.put("planId", OTHER_PLAN);
    assertThrows(BadRequestException.class, () -> validator.apply(schema(), identity));
  }

  @Test
  void checksBooleanAndDeepDefaultsWithoutChangingOriginalPayload() {
    var defaults =
        schema(
            new Field(
                "details",
                Type.OBJECT,
                false,
                Map.of("flag", true),
                null,
                null,
                null,
                null,
                List.of(field("flag", Type.BOOLEAN, true)),
                null));
    var input = payload();
    var output = validator.apply(defaults, input);
    assertEquals(Map.of("flag", true), output.get("details"));
    assertFalse(input.containsKey("details"));
    var bad = payload();
    bad.put("details", Map.of("flag", "true"));
    assertThrows(BadRequestException.class, () -> validator.apply(defaults, bad));
  }
}
