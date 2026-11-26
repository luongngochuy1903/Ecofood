package com.ecofood.app.customer;

import static com.ecofood.app.customer.CustomerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.customer.security.CustomerIdentity;
import com.ecofood.app.customer.security.CustomerReceiptTokens;
import com.ecofood.app.customer.service.ExternalCustomerServiceImpl;
import com.ecofood.core.client.CoreUserClient;
import com.ecofood.core.dto.ExternalCustomerDtos;
import com.ecofood.core.dto.UserCreateRequest;
import com.ecofood.core.dto.UserResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExternalCustomerTest {
  CoreUserClient users = mock(CoreUserClient.class);
  CustomerIdentity identity = mock(CustomerIdentity.class);
  CustomerReceiptTokens tokens = new CustomerReceiptTokens(settings());
  ExternalCustomerServiceImpl service = new ExternalCustomerServiceImpl(users, identity, settings(), tokens);

  @Test
  void issueCreatesACustomerUnderTheOnboardingTenantWhenEmailIsNew() {
    when(identity.matchByEmail("new@example.org")).thenReturn(Optional.empty());
    when(users.createUser(any())).thenReturn(UserResponse.builder().userId("u1").email("new@example.org").build());

    var result = service.issue(new ExternalCustomerDtos.IssueRequest("New@Example.org"));

    assertEquals("new@example.org", result.email());
    assertTrue(result.loginUrl().contains("/customer/login?tenant=" + TENANT));
    assertTrue(result.qrPngBase64().startsWith("iVBOR"));

    var captor = ArgumentCaptor.forClass(UserCreateRequest.class);
    verify(users).createUser(captor.capture());
    assertEquals("new@example.org", captor.getValue().getUsername());
    assertEquals("new@example.org", captor.getValue().getEmail());
    assertEquals(1, captor.getValue().getTenantAssignments().size());
    assertEquals(TENANT, captor.getValue().getTenantAssignments().get(0).getTenantId());
  }

  @Test
  void issueSkipsCreatingADuplicateAccountWhenTheEmailAlreadyHasOne() {
    when(identity.matchByEmail("existing@example.org")).thenReturn(Optional.of("u1"));

    var result = service.issue(new ExternalCustomerDtos.IssueRequest("existing@example.org"));

    assertEquals("existing@example.org", result.email());
    verifyNoInteractions(users);
  }
}
