package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static com.ecofood.app.complaint.ComplaintFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.BadRequestException;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComplaintCategoriesTest {
  @Test void suggestedInvoiceAndPlatformCatalogueDoesNotAutoConfigureInternalCategories() {
    var defaults = ComplaintCategories.suggestedDefaults();
    for (String code : List.of("PRODUCT_ISSUE", "SERVICE_ISSUE", "MISSING_ITEM", "WRONG_ITEM", "OTHER"))
      assertEquals(Kind.INVOICE, ComplaintCategories.require(defaults, Kind.INVOICE, code, T).kind());
    for (String code : List.of("FUNCTIONALITY", "LOGIN", "ACCOUNT", "TRANSACTION", "OTHER"))
      assertEquals(Kind.PLATFORM, ComplaintCategories.require(defaults, Kind.PLATFORM, code, P).kind());
    assertThrows(BadRequestException.class, () -> ComplaintCategories.require(defaults, Kind.INTERNAL, "OTHER", T));
  }
  @Test void categoriesCanBeExtendedAsDataWithoutChangingServiceCode() {
    var catalogue = new ArrayList<>(ComplaintCategories.suggestedDefaults());
    catalogue.add(new ComplaintCategory("SHIFT_SAFETY", Kind.INTERNAL, T, "Shift safety", true));
    catalogue.add(new ComplaintCategory("PACKAGING_DAMAGE", Kind.INVOICE, null, "Packaging damage", true));
    assertEquals("Shift safety", ComplaintCategories.require(catalogue, Kind.INTERNAL, "SHIFT_SAFETY", T).name());
    assertEquals("Packaging damage", ComplaintCategories.require(catalogue, Kind.INVOICE, "PACKAGING_DAMAGE", T).name());
    assertThrows(BadRequestException.class, () -> ComplaintCategories.require(catalogue, Kind.INTERNAL, "SHIFT_SAFETY", M));
  }
  @Test void tenantCategoryOverridesGlobalDefinitionButAmbiguousDuplicatesAreRejected() {
    var catalogue = new ArrayList<>(ComplaintCategories.suggestedDefaults());
    catalogue.add(new ComplaintCategory("OTHER", Kind.INVOICE, T, "Tenant-specific other", true));
    assertEquals("Tenant-specific other", ComplaintCategories.require(catalogue, Kind.INVOICE, "OTHER", T).name());
    catalogue.add(new ComplaintCategory("OTHER", Kind.INVOICE, T, "Duplicate", true));
    assertThrows(BadRequestException.class, () -> ComplaintCategories.require(catalogue, Kind.INVOICE, "OTHER", T));
  }
  @Test void disabledMalformedAndUnknownCategoriesAreRejected() {
    var catalogue = List.of(new ComplaintCategory("DISABLED", Kind.INTERNAL, T, "Disabled", false));
    for (String code : List.of("DISABLED", "UNKNOWN", "../OTHER", "other"))
      assertThrows(BadRequestException.class, () -> ComplaintCategories.require(catalogue, Kind.INTERNAL, code, T));
  }
}
