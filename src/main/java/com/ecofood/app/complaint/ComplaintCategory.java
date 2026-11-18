package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.Kind;

/** null ownerTenantId denotes a global category; INTERNAL categories must be explicitly tenant-owned. */
public record ComplaintCategory(String code, Kind kind, String ownerTenantId, String name, boolean active) {}
