package com.ecofood.app.customer.service;

import com.ecofood.app.customer.config.CustomerSettings;
import com.ecofood.app.customer.security.CustomerIdentity;
import com.ecofood.app.customer.security.CustomerReceiptTokens;
import com.ecofood.core.client.CoreUserClient;
import com.ecofood.core.dto.ExternalCustomerDtos;
import com.ecofood.core.dto.TenantAssignmentRequest;
import com.ecofood.core.dto.UserCreateRequest;
import com.ecofood.core.enums.UserCategoryEnum;
import com.ecofood.core.service.ExternalCustomerService;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
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

  @Override
  public ExternalCustomerDtos.Issued issue(ExternalCustomerDtos.IssueRequest request) {
    String email = request.email().trim().toLowerCase(Locale.ROOT);
    String tenantId = settings.onboardingTenantId();
    // Re-scanning the same email (e.g. a repeat purchase) must not attempt a
    // second account — Keycloak/Mongo would both reject the duplicate
    // username/email outright, and this Customer already has one.
    if (identity.matchByEmail(email).isEmpty()) {
      users.createUser(
          UserCreateRequest.builder()
              .username(email)
              .email(email)
              .userCategoryCode(UserCategoryEnum.CUSTOMER)
              .tenantAssignments(
                  List.of(TenantAssignmentRequest.builder().tenantId(tenantId).build()))
              .build());
    }
    String loginUrl = settings.base() + "/customer/login?tenant=" + tenantId;
    return new ExternalCustomerDtos.Issued(email, loginUrl, tokens.qr(loginUrl));
  }
}
