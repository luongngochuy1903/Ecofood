package com.ecofood.app.notification;

import com.ecofood.app.config.BadRequestException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** Local module contract for a future trusted event adapter; not a public create endpoint. */
public final class NotificationContracts {
  private NotificationContracts() {}
  public enum Type { TRANSACTIONAL, ACCOUNT, SERVICE, MARKETING, COMPLAINT }
  public enum Channel { IN_APP, EMAIL }
  public enum DeliveryStatus { AVAILABLE, PENDING, SENT, FAILED, SUPPRESSED, CAPTURED }
  // FEEDBACK / FEEDBACK_DETAIL are read-only BSON compatibility for archived legacy documents, never new events.
  public enum ReferenceType { INVOICE, FEEDBACK, ACCOUNT, COMPLAINT }
  public enum ActionKind { INVOICE_DETAIL, FEEDBACK_DETAIL, PROFILE, COMPLAINT_DETAIL }
  public record ActionTarget(ActionKind kind, String resourceId) {}
  public record Event(String eventId, String recipientCustomerId, String sourceTenantId, String distributionPartnerId,
      Type type, ReferenceType referenceType, String referenceId, Channel deliveryChannel) {}
  public record CreationResult(String notificationId, DeliveryStatus deliveryStatus, boolean duplicate) {}
  public record DeliveryDecision(boolean allowed, String reason) {}
  public record NotificationView(String notificationId, String sourceTenantId, String distributionPartnerId, Type type,
      String title, String message, ReferenceType referenceType, String referenceId, ActionTarget actionTarget,
      Channel deliveryChannel, DeliveryStatus deliveryStatus, boolean isRead, Instant readAt, Instant createdAt, Instant sentAt, Instant updatedAt) {}
  public record ReadRequest(@NotNull Boolean isRead) {
    @JsonAnySetter public void reject(String field, Object ignored) { throw new BadRequestException("Unsupported notification field"); }
  }
  public record ReadAllRequest() {
    @JsonAnySetter public void reject(String field, Object ignored) { throw new BadRequestException("Unsupported notification field"); }
  }
  public record UnreadCount(long unreadCount) {}
  public record ReadAllResult(long modifiedCount, Instant readAt) {}
  public record PreferenceRequest(Boolean serviceInAppEnabled, Boolean transactionalEmailEnabled,
      Boolean marketingInAppEnabled, Boolean marketingEmailEnabled, Boolean marketingConsent) {
    @JsonAnySetter public void reject(String field, Object ignored) { throw new BadRequestException("Unsupported preference field"); }
  }
  public record ConsentChange(Instant at, String action, String source, String scope, String policyVersion, String actorCustomerId) {}
  public record PreferenceView(String customerId, String consentScope, boolean essentialInAppEnabled,
      boolean serviceInAppEnabled, boolean transactionalEmailEnabled, boolean marketingInAppEnabled, boolean marketingEmailEnabled,
      boolean marketingConsent, Instant marketingConsentAt, String marketingConsentSource, Instant marketingConsentWithdrawnAt, Instant updatedAt) {}
}
