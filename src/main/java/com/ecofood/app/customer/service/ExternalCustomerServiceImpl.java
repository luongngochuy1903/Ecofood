package com.ecofood.app.customer.service;

import com.ecofood.app.customer.config.CustomerSettings;
import com.ecofood.app.customer.entity.CustomerUser;
import com.ecofood.app.customer.entity.ExternalCustomerLink;
import com.ecofood.app.customer.repository.ExternalCustomerLinkRepository;
import com.ecofood.app.customer.security.CustomerAccess;
import com.ecofood.app.customer.security.CustomerIdentity;
import com.ecofood.app.customer.security.CustomerReceiptTokens;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.dto.CustomerIdentityDtos;
import com.ecofood.core.dto.ExternalCustomerDtos;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ExternalCustomerService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pre-transaction customer identification — see ExternalCustomerService
 * javadoc for the whole flow. Distinct from CustomerReceiptServiceImpl (that
 * one issues a receipt QR for an already-paid Invoice and stays unchanged;
 * this one issues a QR before any Invoice exists, keyed only by a
 * distribution-partner-generated externalId).
 */
@Service
@RequiredArgsConstructor
public class ExternalCustomerServiceImpl implements ExternalCustomerService {
  private static final String EXTERNAL_CUSTOMER_PATH = "/external-customer";
  private static final String TENANT_ID = "tenantId";
  private final MongoTemplate mongo;
  private final ExternalCustomerLinkRepository repository;
  private final CustomerSettings settings;
  private final CustomerReceiptTokens tokens;
  private final CustomerIdentity identity;
  private final CustomerAccess access;

  private record Seller(String user, String tenant) {}

  private Seller seller() {
    String user;
    String tenant;
    try { user = AuthenticationContext.getUserId().toHexString(); tenant = AuthenticationContext.getTenantId().toHexString(); }
    catch (RuntimeException invalid) { throw CustomerAccess.denied(); }
    var record = mongo.findById(new ObjectId(user), UserEntity.class);
    if (record == null || Boolean.TRUE.equals(record.getDeleted()) || !CustomerIdentity.belongs(record, tenant)
        || record.getUserCategory() == null || !"PARTNER".equals(record.getUserCategory().getUserCategoryCode())) throw CustomerAccess.denied();
    return new Seller(user, tenant);
  }

  @Override
  public ExternalCustomerDtos.Issued issue(ExternalCustomerDtos.IssueRequest request) {
    var seller = seller();
    String externalId = request.externalId().trim();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    var existing = repository
        .findByTenantIdAndExternalIdAndRevokedAtIsNullAndLinkedCustomerIdIsNull(seller.tenant(), externalId)
        .filter(link -> link.getExpiresAt().isAfter(now))
        .orElse(null);
    ExternalCustomerLink link = existing;
    if (link == null) {
      link = new ExternalCustomerLink();
      link.setId(CustomerReceiptTokens.hash("ecofood.external.customer.id.v1\n" + seller.tenant() + "\n" + seller.user() + "\n" + externalId + "\n" + UUID.randomUUID()));
      link.setTenantId(seller.tenant());
      link.setIssuedBy(seller.user());
      link.setExternalId(externalId);
      link.setIssuedAt(now);
      link.setExpiresAt(now.plus(Duration.ofHours(settings.receiptHours)));
      String token = tokens.token(link.getId(), now);
      link.setTokenHash(CustomerReceiptTokens.hash(token));
      // Validate trusted origin/QR before persisting the new capability.
      tokens.qr(tokens.url(EXTERNAL_CUSTOMER_PATH, token));
      mongo.insert(link);
    }
    String raw = tokens.token(link.getId(), link.getIssuedAt());
    if (!CustomerReceiptTokens.hash(raw).equals(link.getTokenHash()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "External customer signing key changed; revoke and issue a new link");
    String url = tokens.url(EXTERNAL_CUSTOMER_PATH, raw);
    return new ExternalCustomerDtos.Issued(link.getId(), externalId, url, tokens.qr(url), link.getExpiresAt());
  }

  @Override
  public void revoke(String externalId) {
    var seller = seller();
    var query = Query.query(Criteria.where(TENANT_ID).is(seller.tenant()).and("externalId").is(externalId)
        .and("issuedBy").is(seller.user()).and("revokedAt").is(null));
    if (!mongo.exists(query, ExternalCustomerLink.class)) throw CustomerServiceImpl.unavailable();
    mongo.updateFirst(query, new Update().set("revokedAt", Instant.now()), ExternalCustomerLink.class);
  }

  @Override
  public CustomerIdentityDtos.LoginConfig view(String token) {
    CustomerReceiptTokens.validate(token);
    var link = repository.findByTokenHashAndRevokedAtIsNull(CustomerReceiptTokens.hash(token))
        .filter(l -> l.getExpiresAt().isAfter(Instant.now()) && l.getLinkedCustomerId() == null)
        .orElse(null);
    if (link == null) throw CustomerServiceImpl.unavailable();
    return identity.config(link.getTenantId());
  }

  @Override
  public ExternalCustomerDtos.Linked link(String token) {
    var actor = access.current();
    CustomerReceiptTokens.validate(token);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    var link = repository.findByTokenHashAndRevokedAtIsNull(CustomerReceiptTokens.hash(token)).orElse(null);
    if (link == null || !link.getTenantId().equals(actor.tenantId())) throw CustomerServiceImpl.unavailable();
    if (actor.userId().equals(link.getLinkedCustomerId())) return new ExternalCustomerDtos.Linked(link.getExternalId());
    if (link.getLinkedCustomerId() != null || !link.getExpiresAt().isAfter(now)) throw CustomerServiceImpl.unavailable();
    var query = Query.query(Criteria.where("_id").is(link.getId()).and("linkedCustomerId").is(null)
        .and("revokedAt").is(null).and("expiresAt").gt(now));
    var saved = mongo.findAndModify(query, new Update().set("linkedCustomerId", actor.userId()).set("linkedAt", now),
        FindAndModifyOptions.options().returnNew(true), ExternalCustomerLink.class);
    if (saved == null) {
      saved = mongo.findById(link.getId(), ExternalCustomerLink.class);
      if (saved == null || !actor.userId().equals(saved.getLinkedCustomerId())) throw CustomerServiceImpl.unavailable();
    }
    mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(actor.userId()))),
        new Update().addToSet("externalIds", saved.getExternalId()), CustomerUser.class);
    return new ExternalCustomerDtos.Linked(saved.getExternalId());
  }
}
