package com.ecofood.app.complaint;

/** Server-derived Tenant inbox; not a client-selectable queue or a personnel hierarchy. */
final class InvoiceAuthorityRoute {
  static final String POLICY = "invoice-tenant-authority-v1";
  static final String PREFIX = "invoice-authority:";
  private InvoiceAuthorityRoute() {}
  static String queue(String tenant) { ComplaintValidation.id(tenant); return PREFIX + tenant; }
  static String tenant(String queue) {
    if (queue == null || !queue.startsWith(PREFIX)) return null;
    String tenant = queue.substring(PREFIX.length());
    return tenant.matches("[a-f0-9]{24}") ? tenant : null;
  }
}
