package com.ecofood.app.customer.security;

import com.ecofood.app.auth.service.KeycloakTokenVerifier;
import com.ecofood.app.customer.config.CustomerSettings;
import com.ecofood.app.customer.entity.CustomerUser;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.CustomerIdentityDtos;
import com.ecofood.core.service.CustomerIdentityService;
import com.nimbusds.jwt.JWTClaimsSet;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CustomerIdentity implements CustomerIdentityService {
  private static final String EMAIL = "email";
  private final MongoTemplate mongo;
  private final CoreTenantQueryClient tenants;
  private final KeycloakTokenVerifier verifier;
  private final CustomerSettings settings;
  private volatile boolean identityIndexReady;
  public record ExchangeIdentity(String userId, String tenantId) {}
  @Override
  public CustomerIdentityDtos.LoginConfig config(String id) {
    if (id == null || !ObjectId.isValid(id)) throw unavailable();
    var reference = tenants.propositionReference(id); if (reference.deleted()) throw unavailable();
    var tenant = tenants.getById(id);
    return new CustomerIdentityDtos.LoginConfig(id, tenant.getRealm(), tenant.getClientId(), settings.registrationAllowed(id));
  }
  @Override
  public String bootstrap(CustomerIdentityDtos.Bootstrap request) {
    if (request == null || request.accessToken() == null || request.accessToken().length() > 20000) throw unauthorized();
    JWTClaimsSet claims;
    try { claims = verifier.verify(request.accessToken()); } catch (Exception invalid) { throw unauthorized(); }
    var config = config(request.tenantId());
    if (!config.realm().equals(KeycloakTokenVerifier.extractRealm(claims.getIssuer()))) throw unauthorized();
    verifyClaims(claims, config.clientId());
    String email = email(claims);
    var existing = mongo.findOne(Query.query(Criteria.where(EMAIL).regex("^" + java.util.regex.Pattern.quote(email) + "$", "i").and("deleted").ne(true)), CustomerUser.class);
    if (existing == null) {
      if (!config.registrationEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer registration is not enabled for this Tenant");
      ensureIdentityIndex();
      CustomerUser customer = new CustomerUser(); customer.setId(idForEmail(email)); customer.setEmail(email);
      String username = text(claims, "preferred_username", 100); if (username.isBlank() || !username.matches("\\S+")) throw unauthorized();
      customer.setUsername(username); customer.setFirstName(text(claims, "given_name", 100)); customer.setLastName(text(claims, "family_name", 100));
      customer.setUserCategory(UserCategory.builder().userCategoryCode("CUSTOMER").userCategoryDescription("Customer").build());
      customer.setExternalLoginId(claims.getSubject()); customer.setCustomerIdentityIssuer(claims.getIssuer()); customer.setCustomerEmailVerifiedAt(Instant.now()); customer.setCustomerProfileRevision(0L);
      customer.setCustomerVerifiedEmailHash(CustomerReceiptTokens.hash(email));
      customer.setPartyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(config.tenantId())).relationshipTypeCode("TENANT").build()));
      try { mongo.insert(customer); existing = customer; }
      catch (DuplicateKeyException raced) { existing = mongo.findById(customer.getId(), CustomerUser.class); }
    }
    checkCustomer(existing, claims, config.tenantId()); stamp(existing, claims);
    return existing.getIdAsString();
  }
  /** Existing staff exchange is unchanged; Customer exchanges additionally bind the verified Keycloak subject. */
  public ExchangeIdentity guardExchange(JWTClaimsSet claims) {
    String email = String.valueOf(claims.getClaim(EMAIL)).trim();
    var user = mongo.findOne(Query.query(Criteria.where(EMAIL).regex("^" + java.util.regex.Pattern.quote(email) + "$", "i")), CustomerUser.class);
    if (user == null || user.getUserCategory() == null || !"CUSTOMER".equals(user.getUserCategory().getUserCategoryCode())) return null;
    var candidates = tenants.getAllByRealm(KeycloakTokenVerifier.extractRealm(claims.getIssuer()));
    var tenant = candidates.stream().filter(t -> belongs(user, t.getId())).findFirst().orElseThrow(CustomerIdentity::unauthorized);
    verifyClaims(claims, tenant.getClientId()); checkCustomer(user, claims, tenant.getId()); stamp(user, claims);
    return new ExchangeIdentity(user.getIdAsString(), tenant.getId());
  }
  private synchronized void ensureIdentityIndex() {
    if (identityIndexReady) return;
    try {
      mongo.indexOps(CustomerUser.class).createIndex(new org.springframework.data.mongodb.core.index.Index()
          .on(EMAIL, org.springframework.data.domain.Sort.Direction.ASC).unique()
          .collation(org.springframework.data.mongodb.core.query.Collation.of("en").strength(2))
          .partial(org.springframework.data.mongodb.core.index.PartialIndexFilter.of(Criteria.where(EMAIL).type(2)))
          .named("ux_customer_identity_email_ci"));
      identityIndexReady = true;
    } catch (RuntimeException conflict) {
      throw CustomerSettings.unavailable("Customer registration requires a unique case-insensitive app_user.email index; resolve existing duplicate emails without deleting identities");
    }
  }
  private void verifyClaims(JWTClaimsSet claims, String client) {
    try {
      if (!Boolean.TRUE.equals(claims.getBooleanClaim("email_verified")) || claims.getSubject() == null || claims.getSubject().isBlank()
          || !client.equals(claims.getStringClaim("azp"))) throw unauthorized();
    } catch (java.text.ParseException invalid) { throw unauthorized(); }
    email(claims);
  }
  private void checkCustomer(CustomerUser user, JWTClaimsSet claims, String tenant) {
    if (user == null || Boolean.TRUE.equals(user.getDeleted()) || user.getUserCategory() == null || !"CUSTOMER".equals(user.getUserCategory().getUserCategoryCode())
        || !Objects.equals(user.getExternalLoginId(), claims.getSubject()) || !user.getEmail().equalsIgnoreCase(email(claims)) || !belongs(user, tenant)
        || (user.getCustomerIdentityIssuer() != null && !user.getCustomerIdentityIssuer().equals(claims.getIssuer()))) throw unauthorized();
  }
  private void stamp(CustomerUser user, JWTClaimsSet claims) {
    mongo.updateFirst(Query.query(Criteria.where("_id").is(user.getId()).and("externalLoginId").is(claims.getSubject()).and("deleted").ne(true)),
        new Update().set("customerIdentityIssuer", claims.getIssuer()).set("customerEmailVerifiedAt", Instant.now())
            .set("customerVerifiedEmailHash", CustomerReceiptTokens.hash(email(claims))), CustomerUser.class);
  }
  public static boolean belongs(UserEntity user, String tenant) { return user.getPartyRelationships() != null && user.getPartyRelationships().stream().anyMatch(r -> "TENANT".equals(r.getRelationshipTypeCode()) && new ObjectId(tenant).equals(r.getToPartyId())); }
  /**
   * Matches a Customer account by email — used both by
   * ExternalCustomerServiceImpl.issue() (to avoid creating a duplicate
   * account for an email that's already been onboarded) and by
   * submit-transaction auto-link (BatchSalePlanResolver.matchCustomer),
   * matching a Receipt's invoiceHolder.email against it. Ambiguous (more
   * than one match) or no match both return empty — linking is best-effort,
   * never a submission error.
   */
  public Optional<String> matchByEmail(String email) {
    if (email == null || email.isBlank()) return Optional.empty();
    var matches = mongo.find(Query.query(Criteria.where(EMAIL).regex("^" + java.util.regex.Pattern.quote(email.trim()) + "$", "i")
        .and("deleted").ne(true).and("userCategory.userCategoryCode").is("CUSTOMER")), UserEntity.class);
    return matches.size() == 1 ? Optional.of(matches.get(0).getIdAsString()) : Optional.empty();
  }
  private String email(JWTClaimsSet claims) {
    Object raw = claims.getClaim(EMAIL);
    String email = raw instanceof String s ? s.trim().toLowerCase(Locale.ROOT) : "";
    if (!com.ecofood.app.notification.config.NotificationSettings.validEmail(email)) {
      throw unauthorized();
    }
    return email;
  }
  private String text(JWTClaimsSet claims, String key, int limit) { Object raw = claims.getClaim(key); String text = raw instanceof String s ? s.trim() : ""; if (text.length() > limit) throw unauthorized(); return text; }
  private ObjectId idForEmail(String email) {
    try { return new ObjectId(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(("ecofood.customer.identity.v1\n" + email).getBytes(StandardCharsets.UTF_8)), 12)); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  private static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verified Customer identity required"); }
  private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer login unavailable"); }
}
