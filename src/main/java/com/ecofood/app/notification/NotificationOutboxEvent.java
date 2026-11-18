package com.ecofood.app.notification;

import com.ecofood.app.notification.NotificationContracts.*;
import java.time.Instant;
import java.util.UUID;

/** Embedded in the business document and committed by the same single-document Mongo write. */
public record NotificationOutboxEvent(String eventId, Kind kind, String customerId, String portalId,
    String sourceTenantId, String distributionPartnerId, String referenceId, Instant nextAttemptAt,
    int attempts, String leaseId, Instant leaseUntil, String lastError) {
  public enum Kind { INVOICE_CLAIMED, CUSTOMER_REGISTERED, PROFILE_UPDATED, VERIFIED_EMAIL_CHANGED }

  public static NotificationOutboxEvent account(Kind kind, String customerId, String portalId, Instant now) {
    if (kind == Kind.INVOICE_CLAIMED) throw new IllegalArgumentException("Account event required");
    return new NotificationOutboxEvent("account:" + kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + UUID.randomUUID(), kind, customerId, portalId,
        portalId, null, customerId, now, 0, null, null, null);
  }

  public static NotificationOutboxEvent claimed(String linkId, String customerId, String portalId,
      String sellerId, String partnerId, String invoiceId, Instant now) {
    return new NotificationOutboxEvent("invoice-claimed:" + linkId, Kind.INVOICE_CLAIMED, customerId,
        portalId, sellerId, partnerId, invoiceId, now, 0, null, null, null);
  }

  public Event channel(Channel channel) {
    return new Event(eventId, customerId, sourceTenantId, distributionPartnerId,
        kind == Kind.INVOICE_CLAIMED ? Type.TRANSACTIONAL : Type.ACCOUNT,
        kind == Kind.INVOICE_CLAIMED ? ReferenceType.INVOICE : ReferenceType.ACCOUNT, referenceId, channel);
  }
}
