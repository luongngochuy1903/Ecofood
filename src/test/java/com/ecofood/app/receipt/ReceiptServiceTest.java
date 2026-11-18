package com.ecofood.app.receipt;

import com.ecofood.app.config.*;
import com.ecofood.app.receipt.config.ReceiptProperties;
import com.ecofood.app.receipt.dto.*;
import com.ecofood.app.receipt.entity.ReceiptAccess;
import com.ecofood.app.receipt.repository.ReceiptAccessRepository;
import com.ecofood.app.receipt.service.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.SaleOrchestratorConfigProvider;
import io.jsonwebtoken.Jwts;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReceiptServiceTest {
  private static final String ID = "abcdef000000000000000001";
  private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
  private final InvoiceRepository invoices = mock(InvoiceRepository.class);
  private final ReceiptAccessRepository receipts = mock(ReceiptAccessRepository.class);
  private final SaleOrchestratorConfigProvider provider = mock(SaleOrchestratorConfigProvider.class);
  private final ObjectProvider<SaleOrchestratorConfigProvider> providers = mock(ObjectProvider.class);
  private final ReceiptProperties properties = new ReceiptProperties();
  private final ReceiptTokenCodec tokens = new ReceiptTokenCodec(properties);
  private final IssueReceiptRequest request = new IssueReceiptRequest(UUID.fromString("00000000-0000-4000-8000-000000000001"));
  private ReceiptService service;
  private MockedStatic<AuthenticationContext> auth;
  private Invoice invoice;

  @BeforeEach void setup() {
    properties.setTokenSecret(Base64.getEncoder().encodeToString(new byte[32]));
    properties.setPublicBaseUrl("https://receipts.example/api/v1/public/receipts");
    service = new ReceiptService(invoices, receipts, providers, tokens, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    auth = mockStatic(AuthenticationContext.class);
    auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("RECEIPT_ISSUE", "RECEIPT_REVOKE"));
    auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("user").add("tenantId", "tenant-a").build());
    when(providers.getIfAvailable()).thenReturn(provider);
    when(provider.findForPos("tenant-a", "user")).thenReturn(Optional.of(binding("tenant-a", "pos-a", true)));
    invoice = new Invoice(); invoice.setId(ID); invoice.setTenantId("tenant-a"); invoice.setPosId("pos-a");
    invoice.setDistributionPartnerId("DP-A"); invoice.setStatus(TransactionStatus.ACCEPTED);
    invoice.setInvoiceNumber("INV-1"); invoice.setTransactionTime(NOW.minusSeconds(60));
    invoice.setCurrency("VND"); invoice.setTotalAmount(new BigDecimal("20000"));
    invoice.setItems(List.of(new InvoiceItemDto("P1", "Milk", new BigDecimal("2"), new BigDecimal("10000"), new BigDecimal("20000"))));
    when(invoices.findByIdAndTenantIdAndPosIdAndDistributionPartnerId(ID, "tenant-a", "pos-a", "DP-A"))
        .thenReturn(Optional.of(invoice));
    when(receipts.insert(any(ReceiptAccess.class))).thenAnswer(call -> call.getArgument(0));
  }

  @AfterEach void close() { auth.close(); }

  @Test void issuePersistsHashAndTrustedSourceOnly() {
    var response = service.issue(ID, request);
    var captured = org.mockito.ArgumentCaptor.forClass(ReceiptAccess.class);
    verify(receipts).insert(captured.capture());
    ReceiptAccess record = captured.getValue();
    String token = response.receiptUrl().split("token=")[1];
    assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
    assertEquals(tokens.hash(token), record.getTokenHash());
    assertNotEquals(token, record.getTokenHash());
    assertEquals("tenant-a", record.getTenantId()); assertEquals("pos-a", record.getPosId());
    assertEquals(ID, record.getInvoiceId()); assertEquals("DP-A", record.getDistributionPartnerId());
    assertEquals("user", record.getIssuedBy()); assertEquals(NOW.plusSeconds(86400), response.expiresAt());
    assertFalse(record.toString().contains(token));
  }

  @Test void retryReturnsSameUrlAndExpiryWithoutAnotherInsert() {
    ReceiptAccess existing = access();
    when(receipts.findByIdAndInvoiceIdAndTenantIdAndPosId(existing.getId(), ID, "tenant-a", "pos-a"))
        .thenReturn(Optional.of(existing));
    var first = service.issue(ID, request);
    assertEquals(first, service.issue(ID, request));
    assertEquals(existing.getExpiresAt(), first.expiresAt());
    verify(receipts, never()).insert(any(ReceiptAccess.class));
  }

  @Test void concurrentRetryUsesWinningRecordAndDoesNotExtendExpiry() {
    ReceiptAccess winner = access();
    winner.setExpiresAt(NOW.plusSeconds(300));
    when(receipts.findByIdAndInvoiceIdAndTenantIdAndPosId(winner.getId(), ID, "tenant-a", "pos-a"))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(receipts.insert(any(ReceiptAccess.class))).thenThrow(new DuplicateKeyException("race"));
    var response = service.issue(ID, request);
    assertEquals(winner.getExpiresAt(), response.expiresAt());
    assertTrue(response.receiptUrl().endsWith(tokens.token(winner.getId())));
  }

  @Test void newRequestIdExplicitlyIssuesAnotherReceipt() {
    assertNotEquals(service.issue(ID, request).receiptUrl(),
        service.issue(ID, new IssueReceiptRequest(UUID.randomUUID())).receiptUrl());
    verify(receipts, times(2)).insert(any(ReceiptAccess.class));
  }

  @Test void expiredAndRevokedRequestIdsCannotResurrectOrExtendLinks() {
    ReceiptAccess existing = access();
    when(receipts.findByIdAndInvoiceIdAndTenantIdAndPosId(existing.getId(), ID, "tenant-a", "pos-a"))
        .thenReturn(Optional.of(existing));
    existing.setExpiresAt(NOW);
    assertThrows(IllegalStateException.class, () -> service.issue(ID, request));
    existing.setExpiresAt(NOW.plusSeconds(60)); existing.setRevokedAt(NOW);
    assertThrows(IllegalStateException.class, () -> service.issue(ID, request));
    verify(receipts, never()).insert(any(ReceiptAccess.class));
  }

  @Test void missingPermissionsDenyIssueAndRevokeWithoutReadingStorage() {
    auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
    assertThrows(ForbiddenException.class, () -> service.issue(ID, request));
    assertThrows(ForbiddenException.class, () -> service.revoke(ID, "a".repeat(64)));
    verifyNoInteractions(invoices, receipts, providers);
  }

  @Test void tenantOrPosCannotIssueAnotherSourcesInvoice() {
    when(provider.findForPos("tenant-a", "user")).thenReturn(Optional.of(binding("tenant-a", "pos-b", true)));
    assertThrows(IllegalArgumentException.class, () -> service.issue(ID, request));
    verify(invoices).findByIdAndTenantIdAndPosIdAndDistributionPartnerId(ID, "tenant-a", "pos-b", "DP-A");
    auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("user").add("tenantId", "tenant-b").build());
    when(provider.findForPos("tenant-b", "user")).thenReturn(Optional.of(binding("tenant-b", "pos-a", true)));
    assertThrows(IllegalArgumentException.class, () -> service.issue(ID, request));
    verify(invoices).findByIdAndTenantIdAndPosIdAndDistributionPartnerId(ID, "tenant-b", "pos-a", "DP-A");
    verifyNoInteractions(receipts);
  }

  @Test void rejectsMissingTenantOrSubjectAndInvalidBinding() {
    auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("user").build());
    assertThrows(ForbiddenException.class, () -> service.issue(ID, request));
    auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().add("tenantId", "tenant-a").build());
    assertThrows(ForbiddenException.class, () -> service.issue(ID, request));
    auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("user").add("tenantId", "tenant-a").build());
    when(provider.findForPos("tenant-a", "user")).thenReturn(Optional.of(binding("tenant-b", "pos-a", true)));
    assertThrows(ForbiddenException.class, () -> service.issue(ID, request));
    when(provider.findForPos("tenant-a", "user")).thenReturn(Optional.of(binding("tenant-a", "pos-a", false)));
    assertThrows(ForbiddenException.class, () -> service.issue(ID, request));
    when(provider.findForPos("tenant-a", "user")).thenReturn(Optional.empty());
    assertThrows(ForbiddenException.class, () -> service.issue(ID, request));
    verifyNoInteractions(invoices, receipts);
  }

  @Test void refusesInvalidInvoiceAndUnavailableProvider() {
    assertThrows(BadRequestException.class, () -> service.issue("invalid", request));
    invoice.setStatus(null);
    assertThrows(IllegalStateException.class, () -> service.issue(ID, request));
    when(providers.getIfAvailable()).thenReturn(null);
    assertEquals(503, assertThrows(ResponseStatusException.class, () -> service.issue(ID, request)).getStatusCode().value());
    verifyNoInteractions(receipts);
  }

  @Test void normalizesInvoiceIdForRetryIdentity() {
    when(invoices.findByIdAndTenantIdAndPosIdAndDistributionPartnerId(ID.toUpperCase(), "tenant-a", "pos-a", "DP-A"))
        .thenReturn(Optional.of(invoice));
    assertEquals(service.issue(ID, request), service.issue(ID.toUpperCase(), request));
  }

  @Test void rejectsMissingRequestAndBadConfigurationWithoutPersisting() {
    assertThrows(BadRequestException.class, () -> service.issue(ID, null));
    properties.setTtl(Duration.ZERO);
    assertThrows(ResponseStatusException.class, () -> service.issue(ID, request));
    properties.setTtl(Duration.ofDays(31));
    assertThrows(ResponseStatusException.class, () -> service.issue(ID, request));
    properties.setTtl(Duration.ofHours(24)); properties.setPublicBaseUrl("https://untrusted.example/receipt?redirect=bad");
    assertThrows(ResponseStatusException.class, () -> service.issue(ID, request));
    properties.setPublicBaseUrl("https://receipts.example/receipt"); properties.setTokenSecret("");
    assertThrows(ResponseStatusException.class, () -> service.issue(ID, request));
    verify(receipts, never()).insert(any(ReceiptAccess.class));
  }

  @Test void keyRotationRequiresNewRequestIdButDoesNotChangeExistingTokenHash() {
    var existing = access();
    when(receipts.findByIdAndInvoiceIdAndTenantIdAndPosId(existing.getId(), ID, "tenant-a", "pos-a"))
        .thenReturn(Optional.of(existing));
    byte[] rotated = new byte[32]; Arrays.fill(rotated, (byte) 7);
    properties.setTokenSecret(Base64.getEncoder().encodeToString(rotated));
    assertThrows(IllegalStateException.class, () -> service.issue(ID, request));
    verify(receipts, never()).insert(any(ReceiptAccess.class));
  }

  @Test void guestReadUsesReceiptBindingAndNeedsNeitherInternalAuthNorProvider() {
    ReceiptAccess existing = access();
    String token = tokens.token(existing.getId());
    when(receipts.findByTokenHashAndRevokedAtIsNullAndExpiresAtGreaterThan(tokens.hash(token), NOW))
        .thenReturn(Optional.of(existing));
    properties.setTokenSecret("");
    when(providers.getIfAvailable()).thenReturn(null);
    PublicReceiptResponse result = service.read(token);
    assertEquals("INV-1", result.invoiceNumber());
    assertEquals(new BigDecimal("20000"), result.totalAmount());
    assertEquals("DP-A", result.distributionPartner());
    assertEquals("Milk", result.items().get(0).productName());
    verify(invoices).findByIdAndTenantIdAndPosIdAndDistributionPartnerId(ID, "tenant-a", "pos-a", "DP-A");
    auth.verifyNoInteractions(); verifyNoInteractions(providers, provider);
  }

  @Test void invalidMalformedExpiredOrRevokedTokenHasSameGenericNotFound() {
    for (String token : Arrays.asList(null, "", "short", "a".repeat(5000), "a".repeat(43))) {
      assertEquals("Receipt unavailable", assertThrows(IllegalArgumentException.class, () -> service.read(token)).getMessage());
    }
    verifyNoInteractions(invoices);
    verify(receipts, times(1)).findByTokenHashAndRevokedAtIsNullAndExpiresAtGreaterThan(anyString(), eq(NOW));
  }

  @Test void missingOrIneligibleInvoiceCannotBeReadEvenWithValidToken() {
    ReceiptAccess existing = access(); String token = tokens.token(existing.getId());
    when(receipts.findByTokenHashAndRevokedAtIsNullAndExpiresAtGreaterThan(tokens.hash(token), NOW)).thenReturn(Optional.of(existing));
    invoice.setStatus(null);
    assertEquals("Receipt unavailable", assertThrows(IllegalArgumentException.class, () -> service.read(token)).getMessage());
    when(invoices.findByIdAndTenantIdAndPosIdAndDistributionPartnerId(ID, "tenant-a", "pos-a", "DP-A")).thenReturn(Optional.empty());
    assertEquals("Receipt unavailable", assertThrows(IllegalArgumentException.class, () -> service.read(token)).getMessage());
  }

  @Test void revokeIsScopedAndIdempotent() {
    ReceiptAccess existing = access();
    when(receipts.findByIdAndInvoiceIdAndTenantIdAndPosId(existing.getId(), ID, "tenant-a", "pos-a"))
        .thenReturn(Optional.of(existing));
    service.revoke(ID, existing.getId()); service.revoke(ID, existing.getId());
    verify(receipts, times(2)).revoke(existing.getId(), ID, "tenant-a", "pos-a", NOW);
    verify(receipts, never()).save(any(ReceiptAccess.class));
  }

  @Test void cannotRevokeAnotherInvoicesReceiptOrCreateReceiptThroughRevoke() {
    assertThrows(IllegalArgumentException.class, () -> service.revoke(ID, "a".repeat(64)));
    assertThrows(BadRequestException.class, () -> service.revoke(ID, "invalid"));
    verify(receipts, never()).revoke(anyString(), anyString(), anyString(), anyString(), any());
    verify(receipts, never()).insert(any(ReceiptAccess.class));
  }

  private ReceiptAccess access() {
    ReceiptAccess access = new ReceiptAccess();
    access.setId(tokens.receiptId("tenant-a", "pos-a", ID, request.requestId().toString()));
    access.setTokenHash(tokens.hash(tokens.token(access.getId())));
    access.setInvoiceId(ID); access.setTenantId("tenant-a"); access.setPosId("pos-a");
    access.setDistributionPartnerId("DP-A"); access.setIssuedAt(NOW); access.setExpiresAt(NOW.plusSeconds(86400));
    return access;
  }

  private SaleOrchestratorConfig binding(String tenant, String pos, boolean active) {
    return new SaleOrchestratorConfig(tenant, pos, "DP-A", "plan", "prop", active, false, false, Set.of("VND"), 100);
  }
}
