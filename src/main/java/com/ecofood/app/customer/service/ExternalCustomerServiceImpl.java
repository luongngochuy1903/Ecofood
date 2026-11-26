package com.ecofood.app.customer.service;

import com.ecofood.app.customer.config.CustomerSettings;
import com.ecofood.app.customer.security.CustomerIdentity;
import com.ecofood.app.customer.security.CustomerReceiptTokens;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.client.CoreUserClient;
import com.ecofood.core.dto.ExternalCustomerDtos;
import com.ecofood.core.dto.TenantAssignmentRequest;
import com.ecofood.core.dto.UserCreateRequest;
import com.ecofood.core.enums.UserCategoryEnum;
import com.ecofood.core.service.ExternalCustomerService;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Pre-transaction customer identification — see ExternalCustomerService
 * javadoc for the whole flow. Reuses the exact same user-creation path
 * tenant onboarding uses (CoreUserClient.createUser), just with a fixed
 * onboarding Tenant and no roles — a distribution-partner-onboarded Customer
 * never needs any Tenant role, only a Keycloak identity under that Tenant's
 * realm so they can sign in via the existing /customer/login page.
 */
@Service
@RequiredArgsConstructor
public class ExternalCustomerServiceImpl implements ExternalCustomerService {
  private final CoreUserClient users;
  private final CustomerIdentity identity;
  private final CustomerSettings settings;
  private final CustomerReceiptTokens tokens;
  private final MongoTemplate mongo;

  @Override
  public ExternalCustomerDtos.Issued issue(ExternalCustomerDtos.IssueRequest request) {
    String email = request.email().trim().toLowerCase(Locale.ROOT);
    String tenantId = settings.onboardingTenantId();
    // Re-scanning the same email (e.g. a repeat purchase) must not attempt a
    // second account — Keycloak/Mongo would both reject the duplicate
    // username/email outright, and this Customer already has one.
    if (identity.matchByEmail(email).isEmpty()) {
      var created = users.createUser(
          UserCreateRequest.builder()
              .username(email)
              .email(email)
              .userCategoryCode(UserCategoryEnum.CUSTOMER)
              .tenantAssignments(
                  List.of(TenantAssignmentRequest.builder().tenantId(tenantId).build()))
              .build());
      // CoreUserClient.createUser always persists a plain UserEntity — but
      // CustomerIdentity.bootstrap()/CustomerAccess.current() both query
      // specifically against CustomerUser.class, which Spring Data Mongo
      // restricts to documents whose _class discriminator is CustomerUser
      // (or a subtype). Re-stamp it here so this account's first real login
      // is actually found by that existing code, queried against the root
      // UserEntity type so this plain-UserEntity document is still matched.
      mongo.updateFirst(
          Query.query(Criteria.where("_id").is(new ObjectId(created.getUserId()))),
          new Update().set("_class", "com.ecofood.app.customer.CustomerUser").set("customerProfileRevision", 0L),
          UserEntity.class);
    }
    String loginUrl = settings.base() + "/customer/login?tenant=" + tenantId;
    return new ExternalCustomerDtos.Issued(email, loginUrl, tokens.qr(loginUrl));
  }
}
