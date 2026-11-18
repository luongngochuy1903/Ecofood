package com.ecofood.app.customer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.customer.dto.CustomerProfileUpdateRequest;
import com.ecofood.app.customer.entity.CustomerProfileEntity;
import com.ecofood.app.customer.repository.CustomerProfileRepository;
import com.ecofood.app.customer.service.CustomerPortalService;
import com.ecofood.app.userACL.entity.*;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.*;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;

class CustomerProfileServiceTest {
  static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
  final CustomerIdentityService identities = mock(CustomerIdentityService.class);
  final CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
  final MongoTemplate mongo = mock(MongoTemplate.class);
  final CustomerPortalService service = new CustomerPortalService(identities, profiles, mongo, Clock.fixed(NOW, ZoneOffset.UTC));
  final UserEntity user = UserEntity.builder().email("buyer@example.test").externalLoginId("keycloak-subject")
      .customerIdentityIssuer("https://identity.example.test/realms/customerportal")
      .firstName("An").lastName("Nguyen").partyRelationships(List.of(PartyToPartyRelationship.builder()
          .toPartyId(new ObjectId("000000000000000000000002")).build())).build();
  CustomerProfileEntity profile;

  @BeforeEach void setup() {
    user.setId(new ObjectId("000000000000000000000001")); user.setCreatedAt(NOW.minusSeconds(86400)); user.setUpdatedAt(NOW.minusSeconds(3600));
    when(identities.requireSelfServiceAccount(anyString())).thenReturn(user);
    when(profiles.findByTenantIdAndUserIdAndDeletedNot(any(), any(), eq(true))).thenAnswer(call -> Optional.ofNullable(profile));
  }

  @Test void firstGetUsesVerifiedIdentityWithoutCreatingProfileOrAnotherUser() {
    var result = service.getMyProfile();
    assertEquals(user.getIdAsString(), result.customerId()); assertEquals(result.customerId(), result.userId());
    assertEquals("An Nguyen", result.fullName()); assertEquals(user.getEmail(), result.email());
    assertEquals(user.getCreatedAt(), result.createdAt()); assertEquals(user.getUpdatedAt(), result.updatedAt());
    assertEquals(user.getCustomerIdentityIssuer() + "/account/", result.identityManagementUrl());
    verify(identities).requireSelfServiceAccount("CUSTOMER_SELF_READ");
    verify(profiles).findByTenantIdAndUserIdAndDeletedNot(user.getPartyRelationships().get(0).getToPartyId(), user.getId(), true);
    verifyNoInteractions(mongo);
  }

  @Test void partialUpdatePersistsOnlyPermittedFieldsAndReadsBackWithAudit() {
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(CustomerProfileEntity.class)))
        .thenAnswer(call -> {
          profile = CustomerProfileEntity.builder().tenantId(user.getPartyRelationships().get(0).getToPartyId()).userId(user.getId())
              .firstName("Binh").lastName("Nguyen").phoneNumber("+84901234567").build(); profile.setUpdatedAt(NOW); return profile;
        });
    var result = service.updateMyProfile(new CustomerProfileUpdateRequest(" Binh ", null, "+84901234567", null));
    assertEquals("Binh Nguyen", result.fullName()); assertEquals(NOW, result.updatedAt());
    assertEquals(result, service.getMyProfile()); assertEquals("An", user.getFirstName()); assertEquals("buyer@example.test", user.getEmail());
    var update = ArgumentCaptor.forClass(Update.class); var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).findAndModify(query.capture(), update.capture(), any(FindAndModifyOptions.class), eq(CustomerProfileEntity.class));
    assertEquals(user.getId(), query.getValue().getQueryObject().get("userId"));
    Document set = update.getValue().getUpdateObject().get("$set", Document.class);
    assertEquals(Set.of("firstName", "phoneNumber", "updatedAt", "updatedBy"), set.keySet());
    assertEquals("Binh", set.getString("firstName")); assertEquals(NOW, set.get("updatedAt"));
    var push = update.getValue().getUpdateObject().get("$push", Document.class).get("changeHistory", Document.class);
    assertFalse(push.containsKey("$slice")); // preserve existing audit history
    var audit = (Document) ((List<?>) push.get("$each")).get(0);
    assertEquals(List.of("firstName", "phoneNumber"), audit.get("changedFields")); assertEquals(user.getIdAsString(), audit.getString("actorUserId"));
    var event = (com.ecofood.app.notification.NotificationOutboxEvent) update.getValue().getUpdateObject().get("$push", Document.class).get("notificationOutbox");
    assertEquals(com.ecofood.app.notification.NotificationOutboxEvent.Kind.PROFILE_UPDATED, event.kind());
    assertEquals(user.getIdAsString(), event.customerId());
    verify(identities).requireSelfServiceAccount("CUSTOMER_PROFILE_UPDATE");
  }

  @Test void emptyOrUnchangedPatchDoesNotWriteOrChangeTimestamps() {
    assertEquals(user.getUpdatedAt(), service.updateMyProfile(new CustomerProfileUpdateRequest(null, null, null, null)).updatedAt());
    service.updateMyProfile(new CustomerProfileUpdateRequest(" An ", "Nguyen", "", ""));
    verifyNoInteractions(mongo);
  }

  @Test void emptyNameCannotClearIdentityAndPhoneAddressCanBeCleared() {
    assertThrows(com.ecofood.app.config.BadRequestException.class, () -> service.updateMyProfile(new CustomerProfileUpdateRequest("  ", null, null, null)));
    profile = CustomerProfileEntity.builder().firstName("An").lastName("Nguyen").phoneNumber("0901234567").address("Old address").build();
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(CustomerProfileEntity.class))).thenReturn(profile);
    service.updateMyProfile(new CustomerProfileUpdateRequest(null, null, "", ""));
    var update = ArgumentCaptor.forClass(Update.class);
    verify(mongo).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(CustomerProfileEntity.class));
    Document set = update.getValue().getUpdateObject().get("$set", Document.class);
    assertEquals("", set.get("phoneNumber")); assertEquals("", set.get("address")); assertFalse(set.containsKey("firstName"));
  }

  @Test void concurrentFirstPatchRetriesOnlyItsFieldsAgainstCanonicalProfile() {
    profile = CustomerProfileEntity.builder().firstName("An").lastName("Nguyen").phoneNumber("0901234567").build();
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(CustomerProfileEntity.class)))
        .thenThrow(new DuplicateKeyException("unique tenant/user")).thenReturn(profile);
    service.updateMyProfile(new CustomerProfileUpdateRequest(null, null, "0901234567", "New address"));
    var options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(mongo, times(2)).findAndModify(any(Query.class), any(Update.class), options.capture(), eq(CustomerProfileEntity.class));
    assertTrue(options.getAllValues().get(0).isUpsert()); assertFalse(options.getAllValues().get(1).isUpsert());
    verify(profiles, never()).save(any());
  }

  @Test void deletedProfileCannotBeResurrectedThroughDuplicateKeyRetry() {
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(CustomerProfileEntity.class)))
        .thenThrow(new DuplicateKeyException("deleted unique row")).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> service.updateMyProfile(new CustomerProfileUpdateRequest(null, null, "0901234567", null)));
  }

  @Test void invalidSessionAndStorageFailureNeverReportSuccess() {
    when(identities.requireSelfServiceAccount("CUSTOMER_PROFILE_UPDATE")).thenThrow(new com.ecofood.app.config.ForbiddenException("Denied"));
    assertThrows(com.ecofood.app.config.ForbiddenException.class, () -> service.updateMyProfile(new CustomerProfileUpdateRequest("Other", null, null, null)));
    verifyNoInteractions(profiles, mongo);
    doReturn(user).when(identities).requireSelfServiceAccount("CUSTOMER_PROFILE_UPDATE");
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(CustomerProfileEntity.class)))
        .thenThrow(new DataAccessResourceFailureException("Database unavailable"));
    assertThrows(DataAccessResourceFailureException.class, () -> service.updateMyProfile(new CustomerProfileUpdateRequest("Other", null, null, null)));
  }
}
