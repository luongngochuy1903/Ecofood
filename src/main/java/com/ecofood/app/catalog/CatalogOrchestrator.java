package com.ecofood.app.catalog;

import java.util.List;

/** UML CatalogOrchestrator: catalog composition, not publication lifecycle or sale execution. */
public record CatalogOrchestrator(String id, List<CustomField> customField, List<String> planId) {
  /** Display metadata only; never a Mongo field path, expression or DTO field selector. */
  public record CustomField(String key, String value) {}
}
