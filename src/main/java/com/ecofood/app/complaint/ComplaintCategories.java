package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.Kind;
import com.ecofood.app.config.BadRequestException;
import java.util.*;

/** Suggested seed catalogue only. Nothing is seeded or written at application startup. */
public final class ComplaintCategories {
  private ComplaintCategories() {}
  public static List<ComplaintCategory> suggestedDefaults() {
    return List.of(global("PRODUCT_ISSUE", Kind.INVOICE, "Product Issue"), global("SERVICE_ISSUE", Kind.INVOICE, "Service Issue"),
        global("MISSING_ITEM", Kind.INVOICE, "Missing Item"), global("WRONG_ITEM", Kind.INVOICE, "Wrong Item"), global("OTHER", Kind.INVOICE, "Other"),
        global("FUNCTIONALITY", Kind.PLATFORM, "Functionality"), global("LOGIN", Kind.PLATFORM, "Login"),
        global("ACCOUNT", Kind.PLATFORM, "Account"), global("TRANSACTION", Kind.PLATFORM, "Transaction"), global("OTHER", Kind.PLATFORM, "Other"),
        global("LOGIN_ISSUE", Kind.PLATFORM, "Login Issue"), global("ACCOUNT_ISSUE", Kind.PLATFORM, "Account Issue"),
        global("CUSTOMER_PORTAL_ISSUE", Kind.PLATFORM, "Customer Portal Issue"), global("TRANSACTION_HISTORY_ISSUE", Kind.PLATFORM, "Transaction History Issue"),
        global("NOTIFICATION_ISSUE", Kind.PLATFORM, "Notification Issue"), global("SYSTEM_ERROR", Kind.PLATFORM, "System Error"));
  }
  private static ComplaintCategory global(String code, Kind kind, String name) { return new ComplaintCategory(code, kind, null, name, true); }
  public static ComplaintCategory require(List<ComplaintCategory> catalogue, Kind kind, String code, String tenantId) {
    if (kind == null || code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new BadRequestException("Invalid complaint category");
    var matches = catalogue.stream().filter(c -> c.active() && c.kind() == kind && code.equals(c.code())
        && (Objects.equals(c.ownerTenantId(), tenantId) || kind != Kind.INTERNAL && c.ownerTenantId() == null)).toList();
    var local = matches.stream().filter(c -> c.ownerTenantId() != null).toList();
    var selected = local.isEmpty() ? matches : local;
    if (selected.size() != 1 || selected.get(0).name() == null || selected.get(0).name().isBlank())
      throw new BadRequestException("Complaint category unavailable or ambiguous");
    return selected.get(0);
  }
}
