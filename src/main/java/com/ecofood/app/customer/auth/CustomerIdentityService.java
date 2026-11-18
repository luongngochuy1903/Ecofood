package com.ecofood.app.customer.auth;

import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.core.enums.UserCategoryEnum;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.nimbusds.jwt.JWTClaimsSet;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Customer identities belong only to the server-selected portal Tenant. Never auto-link by email. */
@Service
@RequiredArgsConstructor
public class CustomerIdentityService {
  private final UserRepository users;
  private final MongoTemplate mongo;
  private final CustomerTenantResolver portalTenants;
  private final CustomerKeycloakAccountService keycloakAccounts;

  public UserEntity provision(JWTClaimsSet claims) {
    var portal = portalTenants.resolve();
    Object emailValue = claims.getClaim("email");
    if (!portal.issuer().equals(claims.getIssuer()) || claims.getSubject() == null || claims.getSubject().isBlank()
        || !portal.clientId().equals(claims.getClaim("azp"))
        || !"Bearer".equals(claims.getClaim("typ")) || !Boolean.TRUE.equals(claims.getClaim("email_verified"))
        || !(emailValue instanceof String) || ((String) emailValue).isBlank()) throw rejected();
    String email = ((String) emailValue).strip().toLowerCase(Locale.ROOT);
    String subject = claims.getSubject();
    keycloakAccounts.requireActiveVerified(portal, subject, email);
    UserEntity existing = findIdentity(portal, subject);
    if (existing != null) return synchronizeVerifiedEmail(existing, email, portal);

    // Existing employee/legacy customer emails require an explicit migration, not implicit account takeover.
    if (users.findByEmailIgnoreCase(email).isPresent()) throw conflict();
    UserEntity user = UserEntity.builder()
        .username("customer-" + UUID.nameUUIDFromBytes((portal.issuer() + "|" + subject).getBytes(StandardCharsets.UTF_8)))
        .email(email).customerEmailNormalized(email).customerIdentityIssuer(portal.issuer())
        .externalLoginId(subject).firstName(text(claims.getClaim("given_name")))
        .lastName(text(claims.getClaim("family_name")))
        .userCategory(UserCategory.builder().userCategoryCode(UserCategoryEnum.CUSTOMER.getCode())
            .userCategoryDescription(UserCategoryEnum.CUSTOMER.getDescription()).build())
        .partyRelationships(List.of(PartyToPartyRelationship.builder()
            .toPartyId(new ObjectId(portal.tenantId()))
            .relationshipTypeCode(RelationshipTypeEnum.TENANT.getCode())
            .relationshipTypeDecription(RelationshipTypeEnum.TENANT.getDescription())
            .relationshipReferenceCode(portal.realm()).relationshipReferenceName(portal.tenantName()).build())).build();
    user.setId(new ObjectId());
    user.setNotificationOutbox(List.of(com.ecofood.app.notification.NotificationOutboxEvent.account(
        com.ecofood.app.notification.NotificationOutboxEvent.Kind.CUSTOMER_REGISTERED, user.getIdAsString(), portal.tenantId(), java.time.Instant.now())));
    try { return users.insert(user); }
    catch (DuplicateKeyException ex) {
      // The unique identity index resolves concurrent first logins; never create another user on retry.
      existing = findIdentity(portal, subject);
      if (existing != null) return synchronizeVerifiedEmail(existing, email, portal);
      throw conflict();
    }
  }

  public UserEntity requireAccount(String userId) {
    var portal = portalTenants.resolve();
    if (!ObjectId.isValid(userId)) throw rejected();
    UserEntity user = users.findById(new ObjectId(userId)).orElseThrow(this::rejected);
    user = requireLocalAccount(user, user.getCustomerEmailNormalized(), portal);
    keycloakAccounts.requireActiveVerified(portal, user.getExternalLoginId(), user.getCustomerEmailNormalized());
    return user;
  }

  /** Read-only self-service scope; seller Tenant access comes exclusively from a claimed Invoice link. */
  public CustomerSession requirePurchaseSession() {
    var user = requireSelfServiceAccount("CUSTOMER_SELF_READ");
    return new CustomerSession(user.getIdAsString(), user.getPartyRelationships().get(0).getToPartyId().toHexString());
  }

  public UserEntity requireSelfServiceAccount(String permission) {
    var claims = com.ecofood.core.security.AuthenticationContext.getClaims();
    var permissions = com.ecofood.core.security.AuthenticationContext.getPermissions();
    var portal = portalTenants.resolve();
    if (claims == null || !"CUSTOMER".equals(claims.get("identityType"))
        || !"CUSTOMER".equals(claims.get("userCategory")) || permissions == null
        || !permissions.contains(permission) || !portal.tenantId().equals(claims.get("tenantId"))
        || !portal.clientId().equals(claims.get("clientId")))
      throw new com.ecofood.app.config.ForbiddenException("Customer self-service access required");
    return requireAccount(claims.getSubject());
  }

  /** Called only after verified Keycloak issuer/subject/client/email and active account checks in provision. */
  private UserEntity synchronizeVerifiedEmail(UserEntity existing, String email, CustomerTenantResolver.Portal portal) {
    requireLocalAccount(existing, existing.getCustomerEmailNormalized(), portal);
    if (email.equals(existing.getCustomerEmailNormalized())) return existing;
    if (users.findByEmailIgnoreCase(email).filter(user -> !user.getId().equals(existing.getId())).isPresent()) throw conflict();
    var query = new Query(Criteria.where("_id").is(existing.getId()).and("customerIdentityIssuer").is(portal.issuer())
        .and("externalLoginId").is(existing.getExternalLoginId()).and("customerEmailNormalized").is(existing.getCustomerEmailNormalized()).and("deleted").ne(true));
    var update = new org.springframework.data.mongodb.core.query.Update().set("email", email).set("customerEmailNormalized", email)
        .set("updatedAt", java.time.Instant.now()).set("updatedBy", existing.getIdAsString())
        .push("notificationOutbox", com.ecofood.app.notification.NotificationOutboxEvent.account(
            com.ecofood.app.notification.NotificationOutboxEvent.Kind.VERIFIED_EMAIL_CHANGED,
            existing.getIdAsString(), portal.tenantId(), java.time.Instant.now()));
    try {
      var result = mongo.findAndModify(query, update, org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), UserEntity.class);
      if (result != null) return requireLocalAccount(result, email, portal);
      var concurrent = findIdentity(portal, existing.getExternalLoginId());
      if (concurrent != null) return requireLocalAccount(concurrent, email, portal);
      throw conflict();
    } catch (DuplicateKeyException ex) { throw conflict(); }
  }

  public record CustomerSession(String customerId, String customerTenantId) {}

  /** Existing Employee exchange must never mint Tenant privileges for these managed Customer accounts. */
  public boolean isManagedCustomer(String userId) {
    if (!ObjectId.isValid(userId)) return false;
    return users.findById(new ObjectId(userId)).map(user -> user.getCustomerIdentityIssuer() != null).orElse(false);
  }

  private UserEntity findIdentity(CustomerTenantResolver.Portal portal, String subject) {
    return mongo.findOne(new Query(Criteria.where("customerIdentityIssuer").is(portal.issuer())
        .and("externalLoginId").is(subject)), UserEntity.class);
  }

  private UserEntity requireLocalAccount(UserEntity user, String email, CustomerTenantResolver.Portal portal) {
    if (Boolean.TRUE.equals(user.getDeleted()) || user.getUserCategory() == null
        || !UserCategoryEnum.CUSTOMER.getCode().equals(user.getUserCategory().getUserCategoryCode())
        || !portal.issuer().equals(user.getCustomerIdentityIssuer())
        || user.getExternalLoginId() == null || user.getExternalLoginId().isBlank()
        || user.getCustomerEmailNormalized() == null || !user.getCustomerEmailNormalized().equals(email)
        || !user.getCustomerEmailNormalized().equals(user.getEmail())
        || user.getPartyRelationships() == null || user.getPartyRelationships().size() != 1
        || !new ObjectId(portal.tenantId()).equals(user.getPartyRelationships().get(0).getToPartyId())
        || !RelationshipTypeEnum.TENANT.getCode().equals(user.getPartyRelationships().get(0).getRelationshipTypeCode())) throw rejected();
    return user;
  }

  private String text(Object value) { return value instanceof String text ? text : ""; }
  private ResponseStatusException rejected() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer account unavailable or email not verified");
  }
  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Customer account cannot be linked automatically; contact support");
  }
}
