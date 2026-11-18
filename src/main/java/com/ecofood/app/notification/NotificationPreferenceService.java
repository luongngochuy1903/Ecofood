package com.ecofood.app.notification;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.notification.NotificationContracts.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class NotificationPreferenceService {
  public static final String CONSENT_SOURCE = "CUSTOMER_NOTIFICATION_PREFERENCES_API";
  private final CustomerIdentityService identities;
  private final NotificationPreferenceRepository preferences;
  private final Clock clock;

  public PreferenceView getMine() {
    var session = identities.requirePurchaseSession();
    return view(effective(session.customerId(), session.customerTenantId()));
  }

  public PreferenceView updateMine(PreferenceRequest request) {
    var user = identities.requireSelfServiceAccount("CUSTOMER_NOTIFICATION_MANAGE");
    var session = new CustomerIdentityService.CustomerSession(user.getIdAsString(), user.getPartyRelationships().get(0).getToPartyId().toHexString());
    if (request == null) throw new BadRequestException("Preference request required");
    for (int attempt = 0; attempt < 3; attempt++) {
      var value = effective(session.customerId(), session.customerTenantId());
      PreferenceView before = view(value);
      apply(value, request);
      if (before.equals(view(value))) return before;
      value.setUpdatedAt(now()); value.setUpdatedBy(session.customerId());
      if (value.getId() == null) { value.setCreatedAt(now()); value.setCreatedBy(session.customerId()); }
      try { return view(preferences.save(value)); }
      catch (DuplicateKeyException | OptimisticLockingFailureException ex) {
        if (attempt == 2) throw new IllegalStateException("Notification preferences changed; reload and retry");
      }
    }
    throw new IllegalStateException("Notification preferences unavailable");
  }

  /** Read at creation and again by a future delivery adapter. Never exposes a /me recipient override. */
  public NotificationPreference effective(String customer, String portal) {
    return preferences.findByCustomerIdAndCustomerTenantIdAndDeletedNot(customer, portal, true).orElseGet(() -> {
      var value = new NotificationPreference(); value.setCustomerId(customer); value.setCustomerTenantId(portal); return value;
    });
  }

  public boolean allows(NotificationPreference value, Type type, Channel channel) {
    if (type == Type.COMPLAINT) return channel == Channel.IN_APP; // operational case updates; no email delivery in this integration
    if (type == Type.ACCOUNT) return true; // essential security/account messages
    if (type == Type.MARKETING) return value.isMarketingConsent()
        && (channel == Channel.IN_APP ? value.isMarketingInAppEnabled() : value.isMarketingEmailEnabled());
    return channel == Channel.EMAIL ? value.isTransactionalEmailEnabled() : type == Type.TRANSACTIONAL || value.isServiceInAppEnabled();
  }

  private void apply(NotificationPreference value, PreferenceRequest request) {
    boolean marketingInApp = request.marketingInAppEnabled() == null ? value.isMarketingInAppEnabled() : request.marketingInAppEnabled();
    boolean marketingEmail = request.marketingEmailEnabled() == null ? value.isMarketingEmailEnabled() : request.marketingEmailEnabled();
    boolean withdraw = Boolean.FALSE.equals(request.marketingConsent())
        || ((Boolean.FALSE.equals(request.marketingEmailEnabled()) || Boolean.FALSE.equals(request.marketingInAppEnabled())) && !marketingInApp && !marketingEmail);
    if (Boolean.FALSE.equals(request.marketingConsent()) && (Boolean.TRUE.equals(request.marketingInAppEnabled()) || Boolean.TRUE.equals(request.marketingEmailEnabled())))
      throw new BadRequestException("Cannot enable marketing while withdrawing consent");
    boolean consent = withdraw ? false : request.marketingConsent() == null ? value.isMarketingConsent() : request.marketingConsent();
    if (!consent && (marketingInApp || marketingEmail) && !withdraw) throw new BadRequestException("Explicit marketing consent is required");
    if (request.serviceInAppEnabled() != null) value.setServiceInAppEnabled(request.serviceInAppEnabled());
    if (request.transactionalEmailEnabled() != null) value.setTransactionalEmailEnabled(request.transactionalEmailEnabled());
    value.setMarketingInAppEnabled(withdraw ? false : marketingInApp); value.setMarketingEmailEnabled(withdraw ? false : marketingEmail);
    if (consent && !value.isMarketingConsent()) {
      value.setMarketingConsentAt(now()); value.setMarketingConsentSource(CONSENT_SOURCE); audit(value, "GRANTED");
    } else if (withdraw && (value.isMarketingConsent() || value.getMarketingConsentWithdrawnAt() == null)) {
      value.setMarketingConsentWithdrawnAt(now()); audit(value, "WITHDRAWN");
    }
    value.setMarketingConsent(consent);
  }
  private void audit(NotificationPreference value, String action) {
    if (value.getConsentHistory() == null) value.setConsentHistory(new ArrayList<>());
    value.getConsentHistory().add(new ConsentChange(now(), action, CONSENT_SOURCE, "PLATFORM", "1", value.getCustomerId()));
  }
  private PreferenceView view(NotificationPreference value) {
    return new PreferenceView(value.getCustomerId(), "PLATFORM", true, value.isServiceInAppEnabled(), value.isTransactionalEmailEnabled(),
        value.isMarketingInAppEnabled(), value.isMarketingEmailEnabled(), value.isMarketingConsent(), value.getMarketingConsentAt(),
        value.getMarketingConsentSource(), value.getMarketingConsentWithdrawnAt(), value.getUpdatedAt());
  }
  private Instant now() { return Instant.ofEpochMilli(clock.millis()); }
}
