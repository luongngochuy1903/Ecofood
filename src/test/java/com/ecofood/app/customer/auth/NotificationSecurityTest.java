package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.auth.service.*;
import com.ecofood.app.customer.repository.*;
import com.ecofood.app.notification.*;
import com.ecofood.app.notification.NotificationContracts.*;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.repository.UserRepository;
import com.mongodb.client.result.UpdateResult;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ActiveProfiles({"customer-auth-security-test", "notification-security-test"})
@ContextConfiguration(classes = NotificationSecurityTest.Config.class)
@TestPropertySource(properties = {"jwt.secret=" + CustomerAuthSecurityTest.SECRET, "jwt.issuer=app", "jwt.audience=app-api",
    "jwt.tokenValidityInSeconds=1800", "jwt.refreshTokenValidityInSeconds=86400"})
class NotificationSecurityTest {
  static final String BASE = "/api/v1/customers/me/notifications", PREF = "/api/v1/customers/me/notification-preferences";
  static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
  @Autowired WebApplicationContext context;
  @Autowired MongoTemplate mongo;
  @Autowired UserRepository users;
  @Autowired KeycloakTokenVerifier verifier;
  @Autowired CustomerKeycloakAccountService keycloak;
  @Autowired NotificationRepository notes;
  @Autowired NotificationPreferenceRepository preferences;
  @Autowired NotificationService service;
  @Autowired InternalTokenService tokens;
  Map<String, UserEntity> accounts = new ConcurrentHashMap<>();
  Map<ObjectId, Notification> inbox = new ConcurrentHashMap<>();
  Map<String, NotificationPreference> settings = new ConcurrentHashMap<>();
  MockMvc mvc;

  @BeforeEach void setup() {
    reset(mongo, users, verifier, keycloak, notes, preferences); accounts.clear(); inbox.clear(); settings.clear();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenAnswer(call -> accounts.values().stream()
        .filter(user -> user.getExternalLoginId().equals(((Query) call.getArgument(0)).getQueryObject().get("externalLoginId"))).findFirst().orElse(null));
    when(users.findById(any(ObjectId.class))).thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0).toString())));
    when(users.findByEmailIgnoreCase(anyString())).thenAnswer(call -> accounts.values().stream().filter(user -> user.getEmail().equalsIgnoreCase(call.getArgument(0))).findFirst());
    when(users.insert(any(UserEntity.class))).thenAnswer(call -> { UserEntity user = call.getArgument(0); user.setId(new ObjectId()); accounts.put(user.getIdAsString(), user); return user; });
    when(preferences.findByCustomerIdAndCustomerTenantIdAndDeletedNot(anyString(), anyString(), eq(true))).thenAnswer(call -> {
      assertEquals(CustomerIdentityServiceTest.TENANT, call.getArgument(1)); return Optional.ofNullable(settings.get(call.getArgument(0)));
    });
    when(preferences.save(any(NotificationPreference.class))).thenAnswer(call -> {
      NotificationPreference value = call.getArgument(0); if (value.getId() == null) value.setId(new ObjectId());
      value.setVersion(value.getVersion() == null ? 0 : value.getVersion() + 1); settings.put(value.getCustomerId(), value); return value;
    });
    when(notes.findByRecipientCustomerIdAndCustomerTenantIdAndSourceTenantIdAndEventIdAndDeliveryChannel(anyString(), anyString(), anyString(), anyString(), any()))
        .thenAnswer(call -> inbox.values().stream().filter(row -> row.getRecipientCustomerId().equals(call.getArgument(0)) && row.getCustomerTenantId().equals(call.getArgument(1))
            && row.getSourceTenantId().equals(call.getArgument(2)) && row.getEventId().equals(call.getArgument(3)) && row.getDeliveryChannel() == call.getArgument(4)).findFirst());
    when(notes.insert(any(Notification.class))).thenAnswer(call -> { Notification row = call.getArgument(0); row.setId(new ObjectId()); inbox.put(row.getId(), row); return row; });
    when(mongo.count(any(Query.class), eq(Notification.class))).thenAnswer(call -> matching(call.getArgument(0)).size() * 1L);
    when(mongo.find(any(Query.class), eq(Notification.class))).thenAnswer(call -> {
      Query query = call.getArgument(0); return matching(query).stream().skip(query.getSkip()).limit(query.getLimit() == 0 ? Long.MAX_VALUE : query.getLimit()).toList();
    });
    when(mongo.findOne(any(Query.class), eq(Notification.class))).thenAnswer(call -> matching(call.getArgument(0)).stream().findFirst().orElse(null));
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Notification.class))).thenAnswer(call -> {
      Notification row = matching(call.getArgument(0)).stream().findFirst().orElse(null); if (row != null) apply(row, call.getArgument(1)); return row;
    });
    when(mongo.updateMulti(any(Query.class), any(Update.class), eq(Notification.class))).thenAnswer(call -> {
      var rows = matching(call.getArgument(0)); rows.forEach(row -> apply(row, call.getArgument(1))); return UpdateResult.acknowledged(rows.size(), (long) rows.size(), null);
    });
  }
  List<Notification> matching(Query query) {
    var filter = query.getQueryObject();
    assertNotNull(filter.get("recipientCustomerId")); assertNotNull(filter.get("customerTenantId"));
    return inbox.values().stream().filter(row -> row.getRecipientCustomerId().equals(filter.get("recipientCustomerId"))
        && row.getCustomerTenantId().equals(filter.get("customerTenantId")) && !Boolean.TRUE.equals(row.getDeleted())
        && row.getDeliveryChannel() == Channel.IN_APP && row.getDeliveryStatus() == DeliveryStatus.AVAILABLE
        && (!filter.containsKey("_id") || row.getId().equals(filter.get("_id")))
        && (!filter.containsKey("type") || row.getType() == filter.get("type"))
        && (!filter.containsKey("read") || (filter.get("read") instanceof Document condition ? row.isRead() != condition.getBoolean("$ne") : row.isRead() == (Boolean) filter.get("read"))))
        .sorted(Comparator.comparing(Notification::getId).reversed()).toList();
  }
  void apply(Notification row, Update update) {
    var values = update.getUpdateObject().get("$set", Document.class);
    row.setRead(values.getBoolean("read")); row.setUpdatedAt((Instant) values.get("updatedAt"));
    row.setReadAt(values.containsKey("readAt") ? (Instant) values.get("readAt") : null);
  }

  @Test void customerQueriesPagesFiltersUnreadCountAndTogglesReadWithoutChangingDelivery() throws Exception {
    String access = login("buyer-a"); var a = create(access, "event-a"); create(access, "event-b");
    mvc.perform(get(BASE).header("Authorization", "Bearer " + access).param("page", "0").param("size", "1").param("type", "ACCOUNT").param("isRead", "false"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    mvc.perform(get(BASE + "/unread-count").header("Authorization", "Bearer " + access)).andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(2));
    mvc.perform(patch(BASE + "/" + a.notificationId() + "/read").header("Authorization", "Bearer " + access).contentType("application/json").content("{\"isRead\":true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.isRead").value(true)).andExpect(jsonPath("$.deliveryStatus").value("AVAILABLE"));
    mvc.perform(get(BASE + "/unread-count").header("Authorization", "Bearer " + access)).andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
    mvc.perform(patch(BASE + "/" + a.notificationId() + "/read").header("Authorization", "Bearer " + access).contentType("application/json").content("{\"isRead\":false}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isEmpty());
    mvc.perform(patch(BASE + "/read-all").header("Authorization", "Bearer " + access)).andExpect(status().isOk()).andExpect(jsonPath("$.modifiedCount").value(2));
    mvc.perform(patch(BASE + "/read-all").header("Authorization", "Bearer " + access)).andExpect(status().isOk()).andExpect(jsonPath("$.modifiedCount").value(0));
  }
  @Test void customerBCannotReadOrMarkAAndRecipientOverridesAreNeverAccepted() throws Exception {
    String a = login("buyer-a"), b = login("buyer-b"); var note = create(a, "event-a"); create(b, "event-b");
    mvc.perform(get(BASE).header("Authorization", "Bearer " + b).param("recipientCustomerId", tokens.verifyToken(a).getSubject()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(patch(BASE + "/" + note.notificationId() + "/read").header("Authorization", "Bearer " + b).contentType("application/json").content("{\"isRead\":true}"))
        .andExpect(status().isNotFound());
    mvc.perform(patch(BASE + "/" + note.notificationId() + "/read").header("Authorization", "Bearer " + a).contentType("application/json")
        .content("{\"isRead\":true,\"recipientCustomerId\":\"other\"}"))
        .andExpect(status().isBadRequest()); assertFalse(inbox.get(new ObjectId(note.notificationId())).isRead());
  }
  @Test void consentDefaultsToOptOutAndExplicitGrantWithdrawalArePersistedForCallerOnly() throws Exception {
    String a = login("buyer-a"), b = login("buyer-b");
    mvc.perform(get(PREF).header("Authorization", "Bearer " + a)).andExpect(status().isOk()).andExpect(jsonPath("$.marketingConsent").value(false));
    mvc.perform(patch(PREF).header("Authorization", "Bearer " + a).contentType("application/json").content("{\"marketingEmailEnabled\":true}"))
        .andExpect(status().isBadRequest());
    mvc.perform(patch(PREF).header("Authorization", "Bearer " + a).contentType("application/json").content("{\"marketingEmailEnabled\":true,\"marketingConsent\":true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.marketingConsent").value(true)).andExpect(jsonPath("$.marketingConsentAt").value(NOW.toString()));
    mvc.perform(get(PREF).header("Authorization", "Bearer " + b)).andExpect(status().isOk()).andExpect(jsonPath("$.marketingEmailEnabled").value(false));
    mvc.perform(patch(PREF).header("Authorization", "Bearer " + a).contentType("application/json").content("{\"marketingConsent\":false}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.marketingEmailEnabled").value(false)).andExpect(jsonPath("$.marketingConsentWithdrawnAt").value(NOW.toString()));
    assertEquals(2, settings.get(tokens.verifyToken(a).getSubject()).getConsentHistory().size());
  }
  @Test void protectedPreferenceFieldsInvalidFiltersAndUnsupportedCreateRoutesAreRejected() throws Exception {
    String a = login("buyer-a");
    for (String body : List.of("{\"customerId\":null}", "{\"marketingConsentAt\":\"2026-01-01\"}", "{\"essentialInAppEnabled\":false}", "{\"marketingConsentSource\":\"spoofed\"}"))
      mvc.perform(patch(PREF).header("Authorization", "Bearer " + a).contentType("application/json").content(body)).andExpect(status().isBadRequest());
    mvc.perform(get(BASE).header("Authorization", "Bearer " + a).param("type", "UNKNOWN")).andExpect(status().isBadRequest());
    mvc.perform(get(BASE).header("Authorization", "Bearer " + a).param("size", "101")).andExpect(status().isBadRequest());
    mvc.perform(patch(BASE + "/read-all").header("Authorization", "Bearer " + a).contentType("application/json").content("{\"recipientCustomerId\":\"other\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post(BASE).header("Authorization", "Bearer " + a).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(delete(BASE + "/" + new ObjectId()).header("Authorization", "Bearer " + a)).andExpect(status().isForbidden());
  }
  @Test void customerManagePermissionIsRequiredAndOtherIdentitiesRemainExcluded() throws Exception {
    String a = login("buyer-a"); var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(CustomerAuthSecurityTest.SECRET));
    var claims = new HashMap<String, Object>(tokens.verifyToken(a)); claims.put("permissions", List.of("CUSTOMER_SELF_READ"));
    String readOnly = io.jsonwebtoken.Jwts.builder().claims(claims).id("read-only").signWith(key).compact();
    mvc.perform(get(BASE).header("Authorization", "Bearer " + readOnly)).andExpect(status().isOk());
    mvc.perform(patch(PREF).header("Authorization", "Bearer " + readOnly).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    claims.put("identityType", "EMPLOYEE"); String employee = io.jsonwebtoken.Jwts.builder().claims(claims).id("employee").signWith(key).compact();
    mvc.perform(get(BASE).header("Authorization", "Bearer " + employee)).andExpect(status().isForbidden());
    mvc.perform(get(BASE)).andExpect(status().isUnauthorized()); mvc.perform(get(PREF)).andExpect(status().isUnauthorized());
  }
  private String login(String subject) throws Exception {
    when(verifier.verifyForIssuer(subject, CustomerIdentityServiceTest.ISSUER)).thenReturn(new JWTClaimsSet.Builder(CustomerIdentityServiceTest.claims())
        .subject(subject).claim("email", subject + "@example.test").build());
    var result = mvc.perform(post("/api/v1/customer-auth/exchange").contentType("application/json").content("{\"accessToken\":\"" + subject + "\"}"))
        .andExpect(status().isOk()).andReturn();
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
  }
  private CreationResult create(String token, String event) {
    String customer = tokens.verifyToken(token).getSubject();
    return service.create(new Event(event, customer, CustomerIdentityServiceTest.TENANT, null, Type.ACCOUNT, ReferenceType.ACCOUNT, customer, Channel.IN_APP));
  }
  @Configuration @Profile("notification-security-test") @Import(CustomerAuthSecurityTest.Config.class)
  static class Config {
    @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    @Bean NotificationRepository notes() { return mock(NotificationRepository.class); }
    @Bean NotificationPreferenceRepository preferences() { return mock(NotificationPreferenceRepository.class); }
    @Bean CustomerNotificationController controller(NotificationService service, NotificationPreferenceService preferences) {
      return new CustomerNotificationController(service, preferences);
    }
    @Bean NotificationService notificationService(CustomerIdentityService identities, CustomerTenantResolver portal, NotificationRepository notes,
        NotificationPreferenceService preferences, MongoTemplate mongo, Clock clock) {
      return new NotificationService(identities, portal, mock(CustomerPurchaseRepository.class), preferences, notes, mongo, clock, mock(com.ecofood.app.complaint.ComplaintNotificationSource.class));
    }
    @Bean NotificationPreferenceService preferenceService(CustomerIdentityService identities, NotificationPreferenceRepository preferences, Clock clock) {
      return new NotificationPreferenceService(identities, preferences, clock);
    }
  }
}
