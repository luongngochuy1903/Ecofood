package com.ecofood.app.customer;

import static com.ecofood.app.customer.CustomerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.customer.entity.CustomerUser;
import com.ecofood.app.customer.entity.ExternalCustomerLink;
import com.ecofood.app.customer.repository.ExternalCustomerLinkRepository;
import com.ecofood.app.customer.security.CustomerAccess;
import com.ecofood.app.customer.security.CustomerIdentity;
import com.ecofood.app.customer.security.CustomerReceiptTokens;
import com.ecofood.app.customer.service.ExternalCustomerServiceImpl;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.core.dto.CustomerIdentityDtos;
import com.ecofood.core.dto.ExternalCustomerDtos;
import com.ecofood.core.security.AuthenticationContext;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.web.server.ResponseStatusException;

class ExternalCustomerTest {
  AtomicCustomerMongo db;
  ExternalCustomerServiceImpl service;
  CustomerAccess access;
  CustomerIdentity identity;
  ExternalCustomerLinkRepository repository;
  ThreadLocal<CustomerAccess.Actor> actors = ThreadLocal.withInitial(() -> actor(A));
  MockedStatic<AuthenticationContext> authentication;

  @BeforeEach
  void setup() throws Exception {
    db = new AtomicCustomerMongo();
    access = mock(CustomerAccess.class);
    identity = mock(CustomerIdentity.class);
    repository = mock(ExternalCustomerLinkRepository.class);
    service = new ExternalCustomerServiceImpl(db.mongo, repository, settings(), new CustomerReceiptTokens(settings()), identity, access);
    when(access.current()).thenAnswer(ignored -> actors.get());
    when(identity.config(TENANT)).thenReturn(new CustomerIdentityDtos.LoginConfig(TENANT, "test", "test-public", true));
    when(repository.findByTenantIdAndExternalIdAndRevokedAtIsNullAndLinkedCustomerIdIsNull(anyString(), anyString()))
        .thenAnswer(call -> db.find(Query.query(Criteria.where("tenantId").is(call.getArgument(0))
            .and("externalId").is(call.getArgument(1)).and("revokedAt").is(null).and("linkedCustomerId").is(null)), ExternalCustomerLink.class)
            .stream().findFirst());
    when(repository.findByTokenHashAndRevokedAtIsNull(anyString()))
        .thenAnswer(call -> db.find(Query.query(Criteria.where("tokenHash").is(call.getArgument(0)).and("revokedAt").is(null)), ExternalCustomerLink.class)
            .stream().findFirst());
    var seller = user(SELLER);
    seller.setUserCategory(UserCategory.builder().userCategoryCode("PARTNER").build());
    db.insert(seller);
    db.insert(user(A));
    authentication = mockStatic(AuthenticationContext.class);
    authentication.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(SELLER));
    authentication.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(TENANT));
  }

  @AfterEach
  void close() {
    authentication.close();
    actors.remove();
  }

  ExternalCustomerDtos.Issued issue(String externalId) { return service.issue(new ExternalCustomerDtos.IssueRequest(externalId)); }
  String token(ExternalCustomerDtos.Issued issued) { return issued.url().substring(issued.url().indexOf("#token=") + 7); }

  @Test
  void issueGeneratesAQrAndViewSucceedsBeforeAnyoneLinks() {
    var issued = issue("ext-1");
    assertEquals("ext-1", issued.externalId());
    assertTrue(issued.url().contains("/external-customer#token="));
    var config = service.view(token(issued));
    assertEquals(TENANT, config.tenantId());
  }

  @Test
  void reissuingTheSameExternalIdReturnsTheSameTokenWhileStillUnlinked() {
    var first = issue("ext-1");
    var again = issue("ext-1");
    assertEquals(first.url(), again.url());
    assertEquals(first.expiresAt(), again.expiresAt());
  }

  @Test
  void linkAppendsExternalIdToTheCustomersOwnExternalIdsAndIsIdempotent() {
    var token = token(issue("ext-1"));
    var result = service.link(token);
    assertEquals("ext-1", result.externalId());
    var saved = db.converter.read(CustomerUser.class, db.raw(CustomerUser.class, new ObjectId(A)));
    assertEquals(List.of("ext-1"), saved.getExternalIds());
    // Linking again with the same already-linked actor replays successfully.
    assertEquals("ext-1", service.link(token).externalId());
  }

  @Test
  void anotherCustomerCannotLinkAnAlreadyLinkedToken() {
    var token = token(issue("ext-1"));
    service.link(token);
    actors.set(actor(B));
    assertThrows(ResponseStatusException.class, () -> service.link(token));
  }

  @Test
  void viewFailsOnceTheTokenIsLinked() {
    var token = token(issue("ext-1"));
    service.link(token);
    assertThrows(ResponseStatusException.class, () -> service.view(token));
  }

  @Test
  void revokeStopsTheSellerFromReusingAndBlocksView() {
    var token = token(issue("ext-1"));
    service.revoke("ext-1");
    assertThrows(ResponseStatusException.class, () -> service.view(token));
    assertThrows(ResponseStatusException.class, () -> service.revoke("ext-1"));
  }
}
