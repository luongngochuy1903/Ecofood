package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.customer.claim.*;
import com.ecofood.app.receipt.config.ReceiptProperties;
import com.ecofood.app.receipt.entity.ReceiptAccess;
import com.ecofood.app.receipt.repository.ReceiptAccessRepository;
import com.ecofood.app.receipt.service.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.app.auth.service.KeycloakTokenVerifier;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.service.SaleOrchestratorConfigProvider;
import com.nimbusds.jwt.JWTClaimsSet;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles({"customer-auth-security-test", "invoice-claim-security-test"})
@ContextConfiguration(classes = InvoiceClaimSecurityTest.Config.class)
@TestPropertySource(properties = {"jwt.secret=" + CustomerAuthSecurityTest.SECRET, "jwt.issuer=app", "jwt.audience=app-api",
    "jwt.tokenValidityInSeconds=1800", "jwt.refreshTokenValidityInSeconds=86400"})
class InvoiceClaimSecurityTest {
  private static final String BASE = "/api/v1/customer/invoice-claims";
  private static final String SELLER = "000000000000000000000022", INVOICE = "000000000000000000000033", TOKEN = "a".repeat(43);
  private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
  @Autowired WebApplicationContext context;
  @Autowired UserRepository users;
  @Autowired MongoTemplate mongo;
  @Autowired KeycloakTokenVerifier verifier;
  @Autowired CustomerKeycloakAccountService keycloakAccounts;
  @Autowired CoreTenantQueryClient tenants;
  @Autowired ReceiptAccessRepository receiptRows;
  @Autowired InvoiceRepository invoices;
  @Autowired ReceiptTokenCodec codec;
  @Autowired CustomerInvoiceLinkRepository links;
  @Autowired InvoiceClaimProofRepository proofs;
  @Autowired com.ecofood.app.customer.repository.CustomerPurchaseRepository purchases;
  private MockMvc mvc;
  private ReceiptAccess access;
  private final AtomicReference<CustomerInvoiceLink> owner = new AtomicReference<>();
  private final Map<String, UserEntity> userRows = new ConcurrentHashMap<>();
  private final Map<String, InvoiceClaimProof> proofRows = new ConcurrentHashMap<>();

  @BeforeEach void setup() {
    reset(users, mongo, verifier, keycloakAccounts, receiptRows, invoices, links, proofs, purchases);
    owner.set(null); userRows.clear(); proofRows.clear();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(tenants.getById(SELLER)).thenReturn(TenantResponse.builder().id(SELLER).build());
    when(mongo.findOne(any(Query.class), eq(UserEntity.class))).thenAnswer(call -> userRows.values().stream()
        .filter(user -> user.getExternalLoginId().equals(((Query) call.getArgument(0)).getQueryObject().get("externalLoginId"))).findFirst().orElse(null));
    when(users.findByEmailIgnoreCase(anyString())).thenAnswer(call -> userRows.values().stream().filter(user -> user.getEmail().equalsIgnoreCase(call.getArgument(0))).findFirst());
    when(users.findById(any(ObjectId.class))).thenAnswer(call -> Optional.ofNullable(userRows.get(call.getArgument(0).toString())));
    when(users.insert(any(UserEntity.class))).thenAnswer(call -> {
      UserEntity user = call.getArgument(0); user.setId(new ObjectId()); userRows.put(user.getIdAsString(), user); return user;
    });
    access = new ReceiptAccess(); access.setId("a".repeat(64)); access.setTokenHash(codec.hash(TOKEN));
    access.setInvoiceId(INVOICE); access.setTenantId(SELLER); access.setPosId("pos"); access.setDistributionPartnerId("partner");
    access.setExpiresAt(NOW.plusSeconds(3600));
    when(receiptRows.findByTokenHashAndRevokedAtIsNullAndExpiresAtGreaterThan(eq(codec.hash(TOKEN)), eq(NOW)))
        .thenAnswer(call -> access.getRevokedAt() == null && access.getExpiresAt().isAfter(NOW) ? Optional.of(access) : Optional.empty());
    when(receiptRows.findById(access.getId())).thenReturn(Optional.of(access));
    var invoice = new Invoice(); invoice.setId(INVOICE); invoice.setTenantId(SELLER); invoice.setDistributionPartnerId("partner");
    invoice.setStatus(TransactionStatus.ACCEPTED); invoice.setInvoiceNumber("INV-CLAIM-HTTP"); invoice.setCurrency("VND"); invoice.setTotalAmount(BigDecimal.TEN);
    invoice.setItems(List.of(new InvoiceItemDto("P1", "Product", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN)));
    when(invoices.findByIdAndTenantIdAndPosIdAndDistributionPartnerId(INVOICE, SELLER, "pos", "partner")).thenReturn(Optional.of(invoice));
    when(proofs.insert(any(InvoiceClaimProof.class))).thenAnswer(call -> { InvoiceClaimProof proof = call.getArgument(0); proofRows.put(proof.getId(), proof); return proof; });
    when(proofs.findByIdAndCustomerIdAndCustomerTenantId(anyString(), anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(proofRows.get(call.getArgument(0)))
        .filter(proof -> proof.getCustomerId().equals(call.getArgument(1)) && proof.getCustomerTenantId().equals(call.getArgument(2))));
    when(links.findByInvoiceId(INVOICE)).thenAnswer(call -> Optional.ofNullable(owner.get()));
    when(links.insert(any(CustomerInvoiceLink.class))).thenAnswer(call -> {
      CustomerInvoiceLink link = call.getArgument(0); link.setId(new ObjectId());
      if (!owner.compareAndSet(null, link)) throw new DuplicateKeyException("unique owner"); return link;
    });
    when(purchases.detail(any(), eq(INVOICE))).thenAnswer(call -> {
      var session = (CustomerIdentityService.CustomerSession) call.getArgument(0);
      return owner.get() != null && owner.get().getCustomerId().equals(session.customerId())
          && owner.get().getCustomerTenantId().equals(session.customerTenantId()) ? Optional.of(invoice) : Optional.empty();
    });
    when(purchases.list(any(), any(), any())).thenAnswer(call -> {
      var session = (CustomerIdentityService.CustomerSession) call.getArgument(0);
      boolean owned = owner.get() != null && owner.get().getCustomerId().equals(session.customerId());
      return new org.springframework.data.domain.PageImpl<>(owned ? List.of(invoice) : List.of(), call.getArgument(2), owned ? 1 : 0);
    });
    when(purchases.statistics(any(), any(), any())).thenAnswer(call -> {
      var session = (CustomerIdentityService.CustomerSession) call.getArgument(0);
      boolean owned = owner.get() != null && owner.get().getCustomerId().equals(session.customerId());
      return new com.ecofood.app.customer.dto.CustomerStatisticsResponse(owned ? 1 : 0, owned ? List.of(
          new com.ecofood.app.customer.dto.CustomerStatisticsResponse.CurrencySpend("VND", 1, BigDecimal.TEN)) : List.of());
    });

  }





  @Test void statisticsTimezoneAndFilterValidationUseExistingCustomerAuthentication() throws Exception {
    String a = login("buyer-a"); confirm(a, eligibility(a)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/customers/me/statistics").header("Authorization", "Bearer " + a).param("timeZone", "Asia/Ho_Chi_Minh"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalOrders").value(1))
        .andExpect(jsonPath("$.totalSpent[0].currency").value("VND"))
        .andExpect(jsonPath("$.spendingPolicy.refundTrackingAvailable").value(false));
    mvc.perform(get("/api/v1/customers/me/statistics").header("Authorization", "Bearer " + a).param("timeZone", "invalid/zone"))
        .andExpect(status().isBadRequest());
  }
  @Test void retiredFeedbackEndpointsAreNotAccessibleAndNoLegacyControllersRemain() throws Exception {
    String jwt = login("buyer-a");
    for (String path : List.of("/api/v1/customers/me/feedback", "/api/v1/customers/me/feedback/000000000000000000000021", "/api/v1/feedback"))
      mvc.perform(get(path).header("Authorization", "Bearer " + jwt)).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/public/receipts/" + TOKEN + "/feedback").contentType("application/json").content("{\"rating\":5}"))
        .andExpect(status().isUnauthorized());
    var mappings = context.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
    assertTrue(mappings.getHandlerMethods().keySet().stream().noneMatch(mapping -> mapping.toString().contains("feedback")));
  }



  @Test void claimThenReadActualPurchaseWithCustomerJwtAndNoReceiptEvenAfterExpiry() throws Exception {
    String a = login("buyer-a"); confirm(a, eligibility(a)).andExpect(status().isOk());
    access.setExpiresAt(NOW); // purchase ownership survives public capability expiry
    mvc.perform(get("/api/v1/customers/me/invoices").header("Authorization", "Bearer " + a).param("customerId", "another-user"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].invoiceCode").value("INV-CLAIM-HTTP"))
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    mvc.perform(get("/api/v1/customers/me/invoices/" + INVOICE).header("Authorization", "Bearer " + a))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].productName").value("Product"))
        .andExpect(jsonPath("$.subtotal").value(10)).andExpect(jsonPath("$.tenantId").doesNotExist());
    mvc.perform(get("/api/v1/customers/me/statistics").header("Authorization", "Bearer " + a))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalInvoices").value(1));
  }

  @Test void anotherCustomerCannotReadOwnedPurchaseAndUnauthenticatedCallsAreRejected() throws Exception {
    String a = login("buyer-a"), b = login("buyer-b"); confirm(a, eligibility(a)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/customers/me/invoices/" + INVOICE).header("Authorization", "Bearer " + b)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/customers/me/invoices").header("Authorization", "Bearer " + b)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/api/v1/customers/me/statistics").header("Authorization", "Bearer " + b)).andExpect(status().isOk()).andExpect(jsonPath("$.totalInvoices").value(0));
    for (String path : List.of("/api/v1/customers/me/invoices", "/api/v1/customers/me/statistics", "/api/v1/customers/me/invoices/" + INVOICE))
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  @Test void historyRejectsInvalidFiltersUnsupportedMethodsAndEmployeeIdentity() throws Exception {
    String a = login("buyer-a");
    mvc.perform(get("/api/v1/customers/me/invoices").header("Authorization", "Bearer " + a).param("size", "101")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/customers/me/invoices").header("Authorization", "Bearer " + a).param("status", "PAID")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/customers/me/statistics").header("Authorization", "Bearer " + a).param("from", "bad-date")).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/customers/me/invoices").header("Authorization", "Bearer " + a)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/customers/me/other-user").header("Authorization", "Bearer " + a)).andExpect(status().isForbidden());
    var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(CustomerAuthSecurityTest.SECRET));
    String employee = io.jsonwebtoken.Jwts.builder().subject("employee").id("staff-jti").claim("tokenType", "access")
        .claim("identityType", "EMPLOYEE").claim("tenantId", CustomerIdentityServiceTest.TENANT).signWith(key).compact();
    mvc.perform(get("/api/v1/customers/me/invoices").header("Authorization", "Bearer " + employee)).andExpect(status().isForbidden());
  }

  @Test void purchaseScopeRejectsWrongPortalAndMissingPermissionEvenWithValidSignedJwt() throws Exception {
    var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(CustomerAuthSecurityTest.SECRET));
    for (boolean wrongPortal : List.of(true, false)) {
      String access = io.jsonwebtoken.Jwts.builder().subject("000000000000000000000001").id("scope-jti")
          .claim("tokenType", "access").claim("identityType", "CUSTOMER").claim("userCategory", "CUSTOMER")
          .claim("tenantId", wrongPortal ? SELLER : CustomerIdentityServiceTest.TENANT)
          .claim("clientId", CustomerIdentityServiceTest.TENANT + "-public")
          .claim("permissions", wrongPortal ? List.of("CUSTOMER_SELF_READ") : List.of()).signWith(key).compact();
      mvc.perform(get("/api/v1/customers/me/invoices").header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
    }
    verifyNoInteractions(purchases);
  }

  @Test void verifiedCustomerConfirmsThenRepeatedRequestReturnsSameLinkWithoutMutatingInvoice() throws Exception {
    String jwt = login("buyer-a"); String proof = eligibility(jwt);
    assertNull(owner.get());
    var first = confirm(jwt, proof).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLAIMED_BY_YOU"))
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn();
    String linkId = json(first.getResponse().getContentAsString()).get("linkId").asText();
    confirm(jwt, proof).andExpect(status().isOk()).andExpect(jsonPath("$.linkId").value(linkId));
    assertEquals(SELLER, owner.get().getTenantId()); assertEquals(CustomerIdentityServiceTest.TENANT, owner.get().getCustomerTenantId());
    assertNotNull(owner.get().getCreatedBy()); assertEquals(NOW, owner.get().getClaimedAt());
    verify(links, times(1)).insert(any(CustomerInvoiceLink.class)); verify(invoices, never()).save(any()); verify(invoices, never()).insert(any(Invoice.class));
  }

  @Test void anotherCustomerCannotUseProofOrClaimOwnedInvoiceAndStatusDoesNotExposeOwner() throws Exception {
    String a = login("buyer-a"), b = login("buyer-b"); String proofA = eligibility(a), proofB = eligibility(b);
    confirm(b, proofA).andExpect(status().isNotFound()); confirm(a, proofA).andExpect(status().isOk());
    confirm(b, proofB).andExpect(status().isConflict());
    mvc.perform(post(BASE + "/status").header("Authorization", "Bearer " + b).contentType("application/json").content(receiptBody()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLAIMED_BY_ANOTHER_CUSTOMER"))
        .andExpect(jsonPath("$.claimedAt").isEmpty()).andExpect(jsonPath("$.customerId").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist());
  }

  @Test void requiresAuthenticationAndOnlyExactPostRoutesAreAllowed() throws Exception {
    for (String path : List.of(BASE, BASE + "/eligibility", BASE + "/status"))
      mvc.perform(post(path).contentType("application/json").content(receiptBody())).andExpect(status().isUnauthorized());
    String jwt = login("buyer-a");
    mvc.perform(get(BASE + "/status").header("Authorization", "Bearer " + jwt)).andExpect(status().isForbidden());
    mvc.perform(post(BASE + "/extra").header("Authorization", "Bearer " + jwt)).andExpect(status().isForbidden());
  }

  @Test void bodyCannotOverrideCustomerTenantInvoiceOrConfirmation() throws Exception {
    String jwt = login("buyer-a"); String proof = eligibility(jwt);
    for (String field : List.of("customerId", "tenantId", "invoiceId", "distributionPartnerId")) {
      mvc.perform(post(BASE + "/eligibility").header("Authorization", "Bearer " + jwt).contentType("application/json")
          .content("{\"receiptToken\":\"" + TOKEN + "\",\"" + field + "\":\"other\"}"))
          .andExpect(status().isBadRequest());
      mvc.perform(post(BASE).header("Authorization", "Bearer " + jwt).contentType("application/json")
          .content("{\"claimProof\":\"" + proof + "\",\"confirmed\":true,\"" + field + "\":\"other\"}"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(post(BASE).header("Authorization", "Bearer " + jwt).contentType("application/json")
        .content("{\"claimProof\":\"" + proof + "\",\"confirmed\":false}"))
        .andExpect(status().isBadRequest());
    assertNull(owner.get());
  }

  @Test void receiptRevocationExpiryOrCrossTenantBindingAfterPreviewPreventsClaim() throws Exception {
    String jwt = login("buyer-a"); String proof = eligibility(jwt);
    access.setRevokedAt(NOW); confirm(jwt, proof).andExpect(status().isNotFound());
    access.setRevokedAt(null); access.setExpiresAt(NOW); confirm(jwt, proof).andExpect(status().isNotFound());
    access.setExpiresAt(NOW.plusSeconds(3600)); access.setTenantId("000000000000000000000099");
    confirm(jwt, proof).andExpect(status().isNotFound()); assertNull(owner.get());
  }

  private String login(String username) throws Exception {
    when(verifier.verifyForIssuer(username, CustomerIdentityServiceTest.ISSUER)).thenReturn(new JWTClaimsSet.Builder(CustomerIdentityServiceTest.claims())
        .subject(username).claim("email", username + "@example.test").build());
    var result = mvc.perform(post("/api/v1/customer-auth/exchange").contentType("application/json").content("{\"accessToken\":\"" + username + "\"}"))
        .andExpect(status().isOk()).andReturn();
    return json(result.getResponse().getContentAsString()).get("accessToken").asText();
  }
  private String eligibility(String jwt) throws Exception {
    var result = mvc.perform(post(BASE + "/eligibility").header("Authorization", "Bearer " + jwt).contentType("application/json").content(receiptBody()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ELIGIBLE")).andReturn();
    return json(result.getResponse().getContentAsString()).get("claimProof").asText();
  }
  private org.springframework.test.web.servlet.ResultActions confirm(String jwt, String proof) throws Exception {
    return mvc.perform(post(BASE).header("Authorization", "Bearer " + jwt).contentType("application/json")
        .content("{\"claimProof\":\"" + proof + "\",\"confirmed\":true}"));
  }
  private String receiptBody() { return "{\"receiptToken\":\"" + TOKEN + "\"}"; }
  private com.fasterxml.jackson.databind.JsonNode json(String value) throws Exception { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value); }

  @Configuration
  @Profile("invoice-claim-security-test")
  @Import(CustomerAuthSecurityTest.Config.class)
  static class Config {
    @Bean InvoiceRepository invoices() { return mock(InvoiceRepository.class); }
    @Bean ReceiptAccessRepository receiptRows() { return mock(ReceiptAccessRepository.class); }
    @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    @Bean ReceiptTokenCodec codec() { return new ReceiptTokenCodec(new ReceiptProperties()); }
    @Bean ReceiptService receipts(InvoiceRepository invoices, ReceiptAccessRepository receipts, ObjectProvider<SaleOrchestratorConfigProvider> providers, ReceiptTokenCodec codec, Clock clock) {
      return new ReceiptService(invoices, receipts, providers, codec, new ReceiptProperties(), clock);
    }
    @Bean CustomerInvoiceLinkRepository links() { return mock(CustomerInvoiceLinkRepository.class); }
    @Bean InvoiceClaimProofRepository proofs() { return mock(InvoiceClaimProofRepository.class); }
    @Bean com.ecofood.app.customer.repository.CustomerPurchaseRepository purchases() { return mock(com.ecofood.app.customer.repository.CustomerPurchaseRepository.class); }


    @Bean com.ecofood.app.customer.service.CustomerPurchaseService purchaseService(CustomerIdentityService identities,
        com.ecofood.app.customer.repository.CustomerPurchaseRepository purchases, MongoTemplate mongo) {
      return new com.ecofood.app.customer.service.CustomerPurchaseService(identities, purchases);
    }
    @Bean com.ecofood.app.customer.controller.CustomerPortalController purchaseController(com.ecofood.app.customer.service.CustomerPurchaseService purchases) {
      return new com.ecofood.app.customer.controller.CustomerPortalController(mock(com.ecofood.app.customer.service.CustomerPortalService.class), purchases);
    }
    @Bean InvoiceClaimService claims(CustomerIdentityService identities, CustomerTenantResolver resolver, ReceiptService receipts,
        ReceiptTokenCodec codec, CoreTenantQueryClient tenants, CustomerInvoiceLinkRepository links, InvoiceClaimProofRepository proofs, Clock clock) {
      return new InvoiceClaimService(identities, resolver, receipts, codec, tenants, links, proofs, clock);
    }
    @Bean InvoiceClaimController controller(InvoiceClaimService service) { return new InvoiceClaimController(service); }
  }
}
