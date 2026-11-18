package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.repository.UserRepository;
import com.nimbusds.jwt.JWTClaimsSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CustomerIdentityServiceTest {
  static final String TENANT = "000000000000000000000001";
  static final String REALM = "customerportal-12345";
  static final String CLIENT = TENANT + "-public";
  static final String ISSUER = "https://identity.example.test/realms/" + REALM;
  static final String SUBJECT = "keycloak-subject-a";
  static final String EMAIL = "customer@example.test";
  private final UserRepository users = mock(UserRepository.class);
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final CustomerKeycloakAccountService accounts = mock(CustomerKeycloakAccountService.class);
  private final CustomerTenantResolver resolver = mock(CustomerTenantResolver.class);
  private final CustomerIdentityService service = new CustomerIdentityService(users, mongo, resolver, accounts);

  @BeforeEach void configurePortal() { when(resolver.resolve()).thenReturn(portal()); }

  static CustomerTenantResolver.Portal portal() { return new CustomerTenantResolver.Portal(TENANT, "CustomerPortal", REALM, CLIENT, ISSUER); }

  static CustomerAuthProperties properties() {
    var value = new CustomerAuthProperties(); value.setEnabled(true); value.setTenantId(TENANT);
    value.setServerUrl("https://identity.example.test"); return value;
  }
  static JWTClaimsSet claims() {
    return new JWTClaimsSet.Builder().issuer(ISSUER).subject(SUBJECT).claim("email", EMAIL)
        .claim("email_verified", true).claim("azp", CLIENT).claim("typ", "Bearer").build();
  }
  static UserEntity user() {
    var value = UserEntity.builder().username("customer-test").email(EMAIL).customerEmailNormalized(EMAIL)
        .customerIdentityIssuer(ISSUER).externalLoginId(SUBJECT).partyRelationships(List.of(
            PartyToPartyRelationship.builder().toPartyId(new ObjectId(TENANT)).relationshipTypeCode("TENANT").build()))
        .userCategory(UserCategory.builder().userCategoryCode("CUSTOMER").build()).build();
    value.setId(new ObjectId()); return value;
  }

  @Test void createsCustomerBoundOnlyToConfiguredPortalTenantWithoutEmployeeAcl() {
    when(users.insert(any(UserEntity.class))).thenAnswer(call -> { UserEntity value = call.getArgument(0); value.setId(new ObjectId()); return value; });
    UserEntity result = service.provision(claims());
    assertEquals("CUSTOMER", result.getUserCategory().getUserCategoryCode());
    assertEquals(ISSUER, result.getCustomerIdentityIssuer()); assertEquals(SUBJECT, result.getExternalLoginId());
    assertEquals(EMAIL, result.getCustomerEmailNormalized()); assertEquals(1, result.getPartyRelationships().size());
    assertEquals(new ObjectId(TENANT), result.getPartyRelationships().get(0).getToPartyId());
    assertEquals("TENANT", result.getPartyRelationships().get(0).getRelationshipTypeCode());
    assertEquals(1, result.getNotificationOutbox().size());
    assertEquals(com.ecofood.app.notification.NotificationOutboxEvent.Kind.CUSTOMER_REGISTERED, result.getNotificationOutbox().get(0).kind());
    verify(accounts).requireActiveVerified(portal(), SUBJECT, EMAIL);
    verify(users, never()).save(any());
  }

  @Test void repeatLoginUsesSameIssuerSubjectAndDoesNotInsert() {
    var user = user(); when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenReturn(user);
    assertSame(user, service.provision(claims()));
    assertSame(user, service.provision(claims()));
    verify(users, never()).insert(any(UserEntity.class));
    var query = org.mockito.ArgumentCaptor.forClass(Query.class);
    verify(mongo, times(2)).findOne(query.capture(), eq(UserEntity.class));
    assertEquals(ISSUER, query.getValue().getQueryObject().get("customerIdentityIssuer"));
    assertEquals(SUBJECT, query.getValue().getQueryObject().get("externalLoginId"));
  }

  @Test void verifiedKeycloakEmailChangeUpdatesSameIdentityWithoutMovingOwnershipOrChangingRoles() {
    var existing = user(); var updated = user(); updated.setId(existing.getId());
    updated.setEmail("new@example.test"); updated.setCustomerEmailNormalized("new@example.test");
    when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenReturn(existing);
    when(mongo.findAndModify(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class),
        any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(UserEntity.class))).thenReturn(updated);
    var result = service.provision(new JWTClaimsSet.Builder(claims()).claim("email", "new@example.test").build());
    assertEquals(existing.getId(), result.getId()); assertEquals(SUBJECT, result.getExternalLoginId()); assertEquals("CUSTOMER", result.getUserCategory().getUserCategoryCode());
    assertEquals(existing.getPartyRelationships().get(0).getToPartyId(), result.getPartyRelationships().get(0).getToPartyId());
    verify(accounts).requireActiveVerified(portal(), SUBJECT, "new@example.test");
    var query = org.mockito.ArgumentCaptor.forClass(Query.class);
    var update = org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
    verify(mongo).findAndModify(query.capture(), update.capture(), any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(UserEntity.class));
    assertEquals(existing.getId(), query.getValue().getQueryObject().get("_id"));
    assertEquals(ISSUER, query.getValue().getQueryObject().get("customerIdentityIssuer"));
    assertEquals(SUBJECT, query.getValue().getQueryObject().get("externalLoginId"));
    assertEquals(java.util.Set.of("email", "customerEmailNormalized", "updatedAt", "updatedBy"),
        update.getValue().getUpdateObject().get("$set", org.bson.Document.class).keySet());
    var event = (com.ecofood.app.notification.NotificationOutboxEvent) update.getValue().getUpdateObject().get("$push", org.bson.Document.class).get("notificationOutbox");
    assertEquals(com.ecofood.app.notification.NotificationOutboxEvent.Kind.VERIFIED_EMAIL_CHANGED, event.kind());
    assertEquals(existing.getIdAsString(), event.customerId());
    verify(users, never()).insert(any(UserEntity.class)); verify(users, never()).save(any());
  }

  @Test void verifiedEmailCollisionNeverReassignsExistingAccount() {
    var existing = user(); when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenReturn(existing);
    when(users.findByEmailIgnoreCase("other@example.test")).thenReturn(Optional.of(user()));
    assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
        () -> service.provision(new JWTClaimsSet.Builder(claims()).claim("email", "other@example.test").build())).getStatusCode());
    assertEquals(EMAIL, existing.getEmail());
    verify(mongo, never()).findAndModify(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class),
        any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(UserEntity.class));
  }

  @Test void unverifiedOrProviderRejectedEmailNeverChangesMongoIdentity() {
    assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> service.provision(new JWTClaimsSet.Builder(claims())
        .claim("email", "new@example.test").claim("email_verified", false).build())).getStatusCode());
    doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not verified")).when(accounts).requireActiveVerified(portal(), SUBJECT, "new@example.test");
    assertThrows(ResponseStatusException.class, () -> service.provision(new JWTClaimsSet.Builder(claims()).claim("email", "new@example.test").build()));
    verifyNoInteractions(users, mongo);
  }

  @Test void concurrentVerifiedEmailConflictIsSafelyReportedWithoutAnotherUserInsert() {
    when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenReturn(user());
    when(mongo.findAndModify(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class),
        any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(UserEntity.class))).thenThrow(new DuplicateKeyException("email collision"));
    assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
        () -> service.provision(new JWTClaimsSet.Builder(claims()).claim("email", "new@example.test").build())).getStatusCode());
    verify(users, never()).insert(any(UserEntity.class));
  }

  @Test void rejectsUnverifiedWrongIssuerWrongClientAndIdTokensBeforeProvisioning() {
    for (var invalid : List.of(
        new JWTClaimsSet.Builder(claims()).claim("email_verified", false).build(),
        new JWTClaimsSet.Builder(claims()).issuer("https://other.test/realms/employees").build(),
        new JWTClaimsSet.Builder(claims()).claim("azp", "employee-client").build(),
        new JWTClaimsSet.Builder(claims()).claim("typ", "ID").build(),
        new JWTClaimsSet.Builder(claims()).subject("").build(),
        new JWTClaimsSet.Builder(claims()).claim("email", null).build())) {
      assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> service.provision(invalid)).getStatusCode());
    }
    verifyNoInteractions(users, mongo, accounts);
  }

  @Test void emailCollisionNeverLinksOrConvertsAnEmployee() {
    var employee = user(); employee.setCustomerIdentityIssuer(null);
    employee.setUserCategory(UserCategory.builder().userCategoryCode("TENANT_EMPLOYEE").build());
    when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(employee));
    assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.provision(claims())).getStatusCode());
    assertEquals("TENANT_EMPLOYEE", employee.getUserCategory().getUserCategoryCode());
    verify(users, never()).insert(any(UserEntity.class)); verify(users, never()).save(any());
  }

  @Test void concurrentFirstLoginsResolveOneUniqueIdentity() throws Exception {
    var stored = new AtomicReference<UserEntity>();
    var insertsEntered = new CountDownLatch(2);
    when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenAnswer(call -> stored.get());
    when(users.insert(any(UserEntity.class))).thenAnswer(call -> {
      UserEntity value = call.getArgument(0); value.setId(new ObjectId());
      insertsEntered.countDown();
      if (!insertsEntered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Missing concurrent request");
      if (!stored.compareAndSet(null, value)) throw new DuplicateKeyException("ux_customer_issuer_subject");
      return value;
    });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> service.provision(claims()));
      var second = pool.submit(() -> service.provision(claims()));
      assertSame(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    } finally { pool.shutdownNow(); }
  }

  @Test void competingEmailIdentityDoesNotCreateOrLinkAnotherUser() {
    when(users.insert(any(UserEntity.class))).thenThrow(new DuplicateKeyException("ux_customer_email"));
    assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.provision(claims())).getStatusCode());
  }

  @Test void deletedOrReclassifiedLocalAccountCannotLoginOrRefresh() {
    var user = user(); when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenReturn(user);
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    user.setDeleted(true);
    assertThrows(ResponseStatusException.class, () -> service.provision(claims()));
    assertThrows(ResponseStatusException.class, () -> service.requireAccount(user.getIdAsString()));
    user.setDeleted(false); user.setUserCategory(UserCategory.builder().userCategoryCode("TENANT_EMPLOYEE").build());
    assertThrows(ResponseStatusException.class, () -> service.requireAccount(user.getIdAsString()));
  }

  @Test void refreshChecksCurrentKeycloakAccountStatusAndRejectsEmailChange() {
    var user = user(); when(users.findById(user.getId())).thenReturn(Optional.of(user));
    assertSame(user, service.requireAccount(user.getIdAsString()));
    verify(accounts).requireActiveVerified(portal(), SUBJECT, EMAIL);
    when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenReturn(user);
    assertThrows(ResponseStatusException.class, () -> service.provision(new JWTClaimsSet.Builder(claims()).claim("email", "changed@example.test").build()));
  }
  @Test void requestClaimsCannotChangePortalOwnershipAndOtherTenantMembershipIsRejected() {
    when(users.insert(any(UserEntity.class))).thenAnswer(call -> call.getArgument(0));
    var result = service.provision(new JWTClaimsSet.Builder(claims())
        .claim("tenantId", "000000000000000000000099").claim("permissions", List.of("TENANT_ADMIN")).build());
    assertEquals(new ObjectId(TENANT), result.getPartyRelationships().get(0).getToPartyId());
    var existing = user(); existing.getPartyRelationships().get(0).setToPartyId(new ObjectId("000000000000000000000099"));
    when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenReturn(existing);
    assertThrows(ResponseStatusException.class, () -> service.provision(claims()));
  }
}
