package com.ecofood.app.customer;

import static com.ecofood.app.customer.CustomerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.auth.service.KeycloakTokenVerifier;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.nimbusds.jwt.JWTClaimsSet;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.web.server.ResponseStatusException;

class CustomerIdentityTest {
  MongoTemplate mongo = mock(MongoTemplate.class); CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class); KeycloakTokenVerifier verifier = mock(KeycloakTokenVerifier.class);
  CustomerIdentity service = new CustomerIdentity(mongo, tenants, verifier, settings());
  JWTClaimsSet claims(boolean verified, String subject) { return new JWTClaimsSet.Builder().issuer("https://idp.example/realms/customer").subject(subject).claim("email", user(A).getEmail()).claim("email_verified", verified).claim("azp", "customer-public").claim("preferred_username", "customer-a").build(); }
  @BeforeEach void setup() throws Exception {
    when(tenants.propositionReference(TENANT)).thenReturn(new PropositionTenantReference(TENANT, "Test", null, PartnerTypeEnum.BRAND, TenantTypeEnum.STANDALONE, List.of(TENANT), List.of(), false));
    var tenant = TenantResponse.builder().id(TENANT).realm("customer").clientId("customer-public").build();
    when(tenants.getById(TENANT)).thenReturn(tenant); when(tenants.getAllByRealm("customer")).thenReturn(List.of(tenant));
    when(verifier.verify("kc-token")).thenReturn(claims(true, "kc-" + A)); when(mongo.indexOps(CustomerUser.class)).thenReturn(mock(IndexOperations.class));
  }
  @Test void verifiedKeycloakRegistrationCreatesOnlyOneAppUserCustomerAndNoStaffRoles() {
    assertNotNull(service.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, true)));
    var record = org.mockito.ArgumentCaptor.forClass(CustomerUser.class); verify(mongo).insert(record.capture());
    assertEquals("CUSTOMER", record.getValue().getUserCategory().getUserCategoryCode()); assertEquals("kc-" + A, record.getValue().getExternalLoginId()); assertTrue(CustomerIdentity.belongs(record.getValue(), TENANT));
    assertNotNull(record.getValue().getCustomerEmailVerifiedAt()); assertEquals(24, record.getValue().getIdAsString().length());
  }
  @Test void existingIdentityIsReusedAndProfileNamesAreNotOverwrittenByLoginClaims() {
    var existing = user(A); existing.setFirstName("Updated profile"); when(mongo.findOne(any(Query.class), eq(CustomerUser.class))).thenReturn(existing);
    assertEquals(A, service.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, false))); verify(mongo, never()).insert(any());
    verify(mongo).updateFirst(any(Query.class), argThat((UpdateDefinition u) -> !u.getUpdateObject().get("$set", org.bson.Document.class).containsKey("firstName")), eq(CustomerUser.class));
  }
  @Test void firstLoginAfterHostedRegistrationCanProvisionWithoutRepeatingKeycloakSignup() {
    assertNotNull(service.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, false)));
    verify(mongo).insert(any(CustomerUser.class));
  }
  @Test void unverifiedEmailWrongSubjectAndWrongClientCannotTakeOverExistingCustomer() throws Exception {
    when(mongo.findOne(any(Query.class), eq(CustomerUser.class))).thenReturn(user(A));
    when(verifier.verify("kc-token")).thenReturn(claims(false, "kc-" + A)); assertThrows(ResponseStatusException.class, () -> service.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, true)));
    when(verifier.verify("kc-token")).thenReturn(claims(true, "attacker")); assertThrows(ResponseStatusException.class, () -> service.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, true)));
    var wrong = new JWTClaimsSet.Builder(claims(true, "kc-" + A)).claim("azp", "other-client").build(); when(verifier.verify("kc-token")).thenReturn(wrong);
    assertThrows(ResponseStatusException.class, () -> service.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, true))); verify(mongo, never()).insert(any());
  }
  @Test void registrationCannotConvertPartnerIdentityIntoCustomer() {
    var staff = user(A); staff.setUserCategory(UserCategory.builder().userCategoryCode("PARTNER").build()); when(mongo.findOne(any(Query.class), eq(CustomerUser.class))).thenReturn(staff);
    assertThrows(ResponseStatusException.class, () -> service.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, true))); verify(mongo, never()).insert(any());
    assertNull(service.guardExchange(claims(true, "staff-subject")));
  }
  @Test void signupPolicyIsExplicitAndNoEmailIndexIsCreatedWhenDisabled() {
    var disabled = new CustomerIdentity(mongo, tenants, verifier, new CustomerSettings("", "", "", 24));
    assertThrows(ResponseStatusException.class, () -> disabled.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, true))); verify(mongo, never()).insert(any());
  }
  @Test void legacyDuplicateEmailIndexFailureBlocksRegistrationWithoutDeletingData() {
    when(mongo.indexOps(CustomerUser.class)).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicates"));
    assertThrows(ResponseStatusException.class, () -> service.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, true))); verify(mongo, never()).insert(any()); verify(mongo, never()).remove(any(Query.class), any(Class.class));
  }
  @Test void exchangeReturnsVerifiedUserIdInsteadOfResolvingEmailAgain() {
    when(mongo.findOne(any(Query.class), eq(CustomerUser.class))).thenReturn(user(A));
    assertEquals(new CustomerIdentity.ExchangeIdentity(A, TENANT), service.guardExchange(claims(true, "kc-" + A)));
    assertThrows(ResponseStatusException.class, () -> service.guardExchange(claims(true, "wrong")));
  }
  @Test void simultaneousBootstrapUsesOneIdentity() throws Exception {
    var records = new ConcurrentHashMap<org.bson.types.ObjectId, CustomerUser>();
    doAnswer(call -> { CustomerUser user = call.getArgument(0); if (records.putIfAbsent(user.getId(), user) != null) throw new DuplicateKeyException("race"); return user; }).when(mongo).insert(any(CustomerUser.class));
    when(mongo.findById(any(), eq(CustomerUser.class))).thenAnswer(call -> records.get(call.getArgument(0)));
    var pool = Executors.newFixedThreadPool(2);
    try { Callable<String> signup = () -> service.bootstrap(new CustomerIdentity.Bootstrap("kc-token", TENANT, true)); var first = pool.submit(signup); var second = pool.submit(signup); assertEquals(first.get(), second.get()); assertEquals(1, records.size()); }
    finally { pool.shutdownNow(); }
  }
}
