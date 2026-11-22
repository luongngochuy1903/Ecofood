package com.ecofood.app.orchestrator.validation;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.core.dto.SalePayloadSchema;
import com.ecofood.core.dto.SalePayloadSchema.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SaleSchemaValidator {
  private static final Map<String, Type> REQUIRED =
      Map.of(
          "invoiceNumber",
          Type.STRING,
          "transactionTime",
          Type.DATE,
          "currency",
          Type.STRING,
          "totalAmount",
          Type.NUMBER,
          "items",
          Type.ARRAY);
  private static final Map<String, Type> ITEM_REQUIRED =
      Map.of(
          "productCode",
          Type.STRING,
          "productName",
          Type.STRING,
          "quantity",
          Type.NUMBER,
          "unitPrice",
          Type.NUMBER,
          "amount",
          Type.NUMBER);
  private static final Set<String> RESERVED =
      Set.of(
          "tenantId",
          "planId",
          "propositionId",
          "posId",
          "distributionPartnerId",
          "orchestratorId",
          "orchestratorVersion",
          "customerId");

  public List<String> validate(SalePayloadSchema schema) {
    var errors = new ArrayList<String>();
    if (schema == null || schema.fields() == null) return List.of("Payload schema is required");
    definitions(schema.fields(), "payload", 0, errors);
    mandatory(schema.fields(), REQUIRED, "payload", errors);
    for (var f : schema.fields())
      if (f != null) {
        if (f.name() != null && RESERVED.contains(f.name()))
          errors.add("Identity field is server-owned: " + f.name());
        if ("items".equals(f.name()) && f.type() == Type.ARRAY && f.items() != null) {
          if (f.items().type() != Type.OBJECT || f.items().fields() == null)
            errors.add("items must be an array of invoice objects");
          else mandatory(f.items().fields(), ITEM_REQUIRED, "items[]", errors);
        }
      }
    return errors;
  }

  private void mandatory(
      List<Field> fields, Map<String, Type> required, String path, List<String> errors) {
    required.forEach(
        (name, type) -> {
          if (fields.stream()
              .filter(Objects::nonNull)
              .noneMatch(f -> name.equals(f.name()) && f.type() == type && f.required()))
            errors.add(path + "." + name + " must remain required with type " + type);
        });
  }

  private void definitions(List<Field> fields, String path, int depth, List<String> errors) {
    if (depth > 8 || fields.size() > 100) {
      errors.add(path + ": schema exceeds nesting or field limits");
      return;
    }
    var names = new HashSet<String>();
    for (var f : fields) {
      if (f == null) {
        errors.add(path + ": null field");
        continue;
      }
      String key = path + "." + f.name();
      if (f.name() == null
          || !f.name().matches("[A-Za-z][A-Za-z0-9_]{0,99}")
          || !names.add(f.name())) errors.add(key + ": invalid or duplicate field name");
      definition(f, key, depth, errors);
    }
  }

  private void definition(Field f, String path, int depth, List<String> errors) {
    int before = errors.size();
    if (depth > 8) {
      errors.add(path + ": nesting exceeds 8");
      return;
    }
    if (f.type() == null) {
      errors.add(path + ": unsupported type");
      return;
    }
    if ((f.min() != null || f.max() != null) && f.type() != Type.NUMBER)
      errors.add(path + ": numeric limits require NUMBER");
    if (f.min() != null && f.max() != null && f.min().compareTo(f.max()) > 0)
      errors.add(path + ": min exceeds max");
    if (f.format() != null && !("EMAIL".equals(f.format()) && f.type() == Type.STRING))
      errors.add(path + ": only STRING EMAIL format is supported");
    if (f.type() == Type.OBJECT) {
      if (f.fields() == null) errors.add(path + ": OBJECT fields are required");
      else definitions(f.fields(), path, depth + 1, errors);
    } else if (f.fields() != null) errors.add(path + ": fields require OBJECT");
    if (f.type() == Type.ARRAY) {
      if (f.items() == null) errors.add(path + ": ARRAY item definition is required");
      else definition(f.items(), path + "[]", depth + 1, errors);
    } else if (f.items() != null) errors.add(path + ": items require ARRAY");
    if (f.defaultValue() != null && errors.size() == before)
      try {
        value(f, f.defaultValue(), path, depth);
      } catch (BadRequestException ex) {
        errors.add(ex.getMessage());
      }
  }

  public Map<String, Object> apply(SalePayloadSchema schema, Map<String, Object> payload) {
    var errors = validate(schema);
    if (!errors.isEmpty()) throw new BadRequestException(String.join("; ", errors));
    if (payload == null) throw new BadRequestException("Payload is required");
    for (String key : RESERVED)
      if (payload.containsKey(key)) throw bad(key, "identity is server-owned");
    return object(schema.fields(), payload, "payload", 0);
  }

  private Map<String, Object> object(List<Field> fields, Map<?, ?> input, String path, int depth) {
    var result = new LinkedHashMap<String, Object>();
    var allowed = new HashSet<String>();
    for (var f : fields) {
      allowed.add(f.name());
      Object raw = input.containsKey(f.name()) ? input.get(f.name()) : f.defaultValue();
      if (raw == null) {
        if (f.required()) throw bad(path + "." + f.name(), "required field missing");
      } else result.put(f.name(), value(f, raw, path + "." + f.name(), depth + 1));
    }
    for (var key : input.keySet())
      if (!allowed.contains(key)) throw bad(path, "unknown field " + key);
    return result;
  }

  private Object value(Field f, Object raw, String path, int depth) {
    if (depth > 20) throw bad(path, "payload is too deeply nested");
    boolean valid =
        switch (f.type()) {
          case STRING, DATE -> raw instanceof String;
          case NUMBER -> raw instanceof Number;
          case BOOLEAN -> raw instanceof Boolean;
          case OBJECT -> raw instanceof Map;
          case ARRAY -> raw instanceof List;
        };
    if (!valid) throw bad(path, "expected " + f.type());
    if (f.type() == Type.NUMBER) {
      BigDecimal n;
      try {
        n = new BigDecimal(raw.toString());
      } catch (NumberFormatException ex) {
        throw bad(path, "invalid number");
      }
      if (f.min() != null && n.compareTo(f.min()) < 0
          || f.max() != null && n.compareTo(f.max()) > 0)
        throw bad(path, "number is outside allowed range");
    }
    if (f.type() == Type.DATE)
      try {
        Instant.parse((String) raw);
      } catch (DateTimeException ex) {
        throw bad(path, "expected ISO UTC timestamp");
      }
    if ("EMAIL".equals(f.format()) && !((String) raw).matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw bad(path, "invalid email");
    if (f.type() == Type.OBJECT) return object(f.fields(), (Map<?, ?>) raw, path, depth);
    if (f.type() == Type.ARRAY) {
      var list = (List<?>) raw;
      if (list.size() > 1000) throw bad(path, "array exceeds 1000 elements");
      var result = new ArrayList<Object>();
      for (int i = 0; i < list.size(); i++) {
        if (list.get(i) == null) throw bad(path, "null array element");
        result.add(value(f.items(), list.get(i), path + "[" + i + "]", depth + 1));
      }
      return result;
    }
    return raw;
  }

  private BadRequestException bad(String path, String reason) {
    return new BadRequestException(path + ": " + reason);
  }
}
