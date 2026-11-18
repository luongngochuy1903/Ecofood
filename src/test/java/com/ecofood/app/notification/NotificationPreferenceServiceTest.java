package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.notification.NotificationContracts.*;
import com.ecofood.app.userACL.entity.*;
import java.time.*;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;

class NotificationPreferenceServiceTest {
  CustomerIdentityService identities = mock(CustomerIdentityService.class);
  NotificationPreferenceRepository repository = mock(NotificationPreferenceRepository.class);
  NotificationPreferenceService service = new NotificationPreferenceService(identities, repository, Clock.fixed(NotificationServiceTest.NOW, ZoneOffset.UTC));
  NotificationPreference stored;
  @BeforeEach void setup() {
    var user = UserEntity.builder().partyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(NotificationServiceTest.PORTAL)).build())).build();
    user.setId(new ObjectId(NotificationServiceTest.CUSTOMER));
    when(identities.requireSelfServiceAccount("CUSTOMER_NOTIFICATION_MANAGE")).thenReturn(user);
    when(identities.requirePurchaseSession()).thenReturn(new CustomerIdentityService.CustomerSession(NotificationServiceTest.CUSTOMER, NotificationServiceTest.PORTAL));
    when(repository.findByCustomerIdAndCustomerTenantIdAndDeletedNot(anyString(), anyString(), eq(true))).thenAnswer(call -> Optional.ofNullable(stored));
    when(repository.save(any(NotificationPreference.class))).thenAnswer(call -> { stored = call.getArgument(0); stored.setId(new ObjectId()); stored.setVersion(0L); return stored; });
  }
  @Test void defaultsAreOptOutWithoutWritingADocument() {
    var result = service.getMine(); assertFalse(result.marketingConsent()); assertFalse(result.marketingEmailEnabled()); assertFalse(result.marketingInAppEnabled());
    assertNull(result.marketingConsentAt()); assertTrue(result.essentialInAppEnabled()); assertEquals("PLATFORM", result.consentScope()); verify(repository, never()).save(any());
  }
  @Test void marketingRequiresExplicitConsent() {
    assertThrows(BadRequestException.class, () -> service.updateMine(new PreferenceRequest(null, null, null, true, null)));
    assertThrows(BadRequestException.class, () -> service.updateMine(new PreferenceRequest(null, null, true, null, false)));
    verify(repository, never()).save(any());
  }
  @Test void grantAndWithdrawalPersistServerTimestampSourceScopeAndAudit() {
    var granted = service.updateMine(new PreferenceRequest(null, null, true, true, true));
    assertTrue(granted.marketingConsent()); assertEquals(NotificationServiceTest.NOW, granted.marketingConsentAt());
    assertEquals(NotificationPreferenceService.CONSENT_SOURCE, granted.marketingConsentSource()); assertEquals(1, stored.getConsentHistory().size());
    assertEquals(NotificationServiceTest.CUSTOMER, stored.getConsentHistory().get(0).actorCustomerId()); assertEquals("PLATFORM", stored.getConsentHistory().get(0).scope());
    var withdrawn = service.updateMine(new PreferenceRequest(null, null, null, null, false));
    assertFalse(withdrawn.marketingConsent()); assertFalse(withdrawn.marketingEmailEnabled()); assertFalse(withdrawn.marketingInAppEnabled());
    assertEquals(NotificationServiceTest.NOW, withdrawn.marketingConsentWithdrawnAt()); assertEquals(2, stored.getConsentHistory().size());
    assertEquals("WITHDRAWN", stored.getConsentHistory().get(1).action()); assertEquals(granted.marketingConsentAt(), withdrawn.marketingConsentAt());
  }
  @Test void switchingOffLastMarketingChannelRecordsWithdrawal() {
    service.updateMine(new PreferenceRequest(null, null, null, true, true));
    var result = service.updateMine(new PreferenceRequest(null, null, null, false, null));
    assertFalse(result.marketingConsent()); assertNotNull(result.marketingConsentWithdrawnAt());
  }
  @Test void repeatedAndEmptyPatchesDoNotDuplicateConsentOrChangeUpdatedAt() {
    service.updateMine(new PreferenceRequest(null, null, null, true, true)); var before = service.getMine();
    assertEquals(before, service.updateMine(new PreferenceRequest(null, null, null, true, true)));
    assertEquals(before, service.updateMine(new PreferenceRequest(null, null, null, null, null)));
    assertEquals(1, stored.getConsentHistory().size()); verify(repository, times(1)).save(any());
  }
  @Test void aConcurrentWithdrawalCannotBeLostWhenRetryingAnEnableWithoutFreshConsent() {
    var active = new NotificationPreference(); active.setMarketingConsent(true); active.setMarketingInAppEnabled(true); active.setMarketingConsentAt(NotificationServiceTest.NOW);
    var withdrawn = new NotificationPreference();
    when(repository.findByCustomerIdAndCustomerTenantIdAndDeletedNot(anyString(), anyString(), eq(true))).thenReturn(Optional.of(active), Optional.of(withdrawn));
    doThrow(new OptimisticLockingFailureException("concurrent withdrawal")).when(repository).save(any());
    assertThrows(BadRequestException.class, () -> service.updateMine(new PreferenceRequest(null, null, null, true, null)));
    assertFalse(withdrawn.isMarketingConsent()); verify(repository, times(1)).save(any());
  }
  @Test void preferenceConflictsHaveBoundedRetriesAndNeverReturnSuccess() {
    doThrow(new DuplicateKeyException("concurrent creation")).when(repository).save(any());
    assertThrows(IllegalStateException.class, () -> service.updateMine(new PreferenceRequest(false, null, null, null, null)));
    verify(repository, times(3)).save(any());
  }
  @Test void optionalPreferencesDoNotDisableEssentialAccountNotifications() {
    var value = new NotificationPreference(); value.setServiceInAppEnabled(false); value.setTransactionalEmailEnabled(false);
    assertTrue(service.allows(value, Type.ACCOUNT, Channel.EMAIL)); assertTrue(service.allows(value, Type.TRANSACTIONAL, Channel.IN_APP));
    assertFalse(service.allows(value, Type.TRANSACTIONAL, Channel.EMAIL)); assertFalse(service.allows(value, Type.SERVICE, Channel.IN_APP));
    assertFalse(service.allows(value, Type.MARKETING, Channel.EMAIL));
  }
}
