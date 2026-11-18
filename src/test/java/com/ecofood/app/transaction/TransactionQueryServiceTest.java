package com.ecofood.app.transaction;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.transaction.dto.*;
import com.ecofood.app.transaction.dto.TransactionSummaryResponse.CurrencyTotal;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.app.transaction.service.TransactionQueryService;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.entity.RoleLevel;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import io.jsonwebtoken.Jwts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionQueryServiceTest {
  private static final String TENANT = "000000000000000000000001";
  private static final String CHILD = "000000000000000000000002";
  private static final String ID = "000000000000000000000003";
  private final InvoiceRepository invoices = mock(InvoiceRepository.class);
  private final ACLService acl = mock(ACLService.class);
  private final CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
  private final TransactionQueryService service = new TransactionQueryService(invoices, acl, tenants);
  private final TransactionFilter filter = new TransactionFilter(null, null, null, null, null);
  private MockedStatic<AuthenticationContext> auth;

  @BeforeEach void setup() {
    auth = mockStatic(AuthenticationContext.class);
    auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_READ"));
    auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().add("tenantId", TENANT).build());
    when(acl.getMaxLevel()).thenReturn(RoleLevel.TENANT);
  }

  @AfterEach void close() { auth.close(); }

  @Test void tenantListUsesOnlyVerifiedTenantAndStablePagination() {
    when(invoices.findTransactions(eq(List.of(TENANT)), eq(false), eq(filter), any()))
        .thenAnswer(call -> new PageImpl<>(List.of(invoice()), call.getArgument(3), 7));
    var result = service.list(filter, 1, 2);
    assertEquals(7, result.getTotalElements());
    assertEquals(4, result.getTotalPages());
    assertEquals(1, result.getPage());
    assertEquals("INV-1", result.getContent().get(0).invoiceNumber());
    var pagination = org.mockito.ArgumentCaptor.forClass(Pageable.class);
    verify(invoices).findTransactions(eq(List.of(TENANT)), eq(false), eq(filter), pagination.capture());
    assertEquals(2, pagination.getValue().getOffset());
    assertEquals(Sort.Direction.DESC, pagination.getValue().getSort().getOrderFor("id").getDirection());
    verifyNoInteractions(tenants);
  }

  @ParameterizedTest
  @EnumSource(value = RoleLevel.class, names = {"MASTER_TENANT", "ENTERPRISE"})
  void managementLevelsUseExistingTenantScopeForEveryRead(RoleLevel level) {
    when(acl.getMaxLevel()).thenReturn(level);
    when(tenants.getScopedTenantIds(TENANT)).thenReturn(List.of(TENANT, CHILD));
    when(invoices.findTransactions(eq(List.of(TENANT, CHILD)), eq(false), eq(filter), any()))
        .thenAnswer(call -> new PageImpl<>(List.of(), call.getArgument(3), 0));
    when(invoices.findScopedInvoice(ID, List.of(TENANT, CHILD), false)).thenReturn(Optional.of(invoice()));
    when(invoices.summarizeTransactions(List.of(TENANT, CHILD), false, filter)).thenReturn(List.of());
    service.list(filter, 0, 20);
    service.detail(ID);
    service.summary(filter);
    verify(tenants, times(3)).getScopedTenantIds(TENANT);
  }

  @Test void systemCanReadAllTenantsWithoutTenantLookup() {
    when(acl.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    when(invoices.findTransactions(eq(List.of()), eq(true), eq(filter), any()))
        .thenAnswer(call -> new PageImpl<>(List.of(), call.getArgument(3), 0));
    when(invoices.findScopedInvoice(ID, List.of(), true)).thenReturn(Optional.of(invoice()));
    when(invoices.summarizeTransactions(List.of(), true, filter)).thenReturn(List.of());
    service.list(filter, 0, 20);
    service.detail(ID);
    service.summary(filter);
    verifyNoInteractions(tenants);
  }

  @Test void allReadsRejectMissingPermissionBeforeQuerying() {
    auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
    assertThrows(ForbiddenException.class, () -> service.list(filter, 0, 20));
    assertThrows(ForbiddenException.class, () -> service.detail(ID));
    assertThrows(ForbiddenException.class, () -> service.summary(filter));
    verifyNoInteractions(invoices, tenants, acl);
  }

  @ParameterizedTest
  @EnumSource(value = RoleLevel.class, names = {"NONE", "CUSTOMER"})
  void deniesNonEmployeeDataScope(RoleLevel level) {
    when(acl.getMaxLevel()).thenReturn(level);
    assertThrows(ForbiddenException.class, () -> service.list(filter, 0, 20));
    assertThrows(ForbiddenException.class, () -> service.detail(ID));
    assertThrows(ForbiddenException.class, () -> service.summary(filter));
    verifyNoInteractions(invoices, tenants);
  }

  @Test void rejectsMissingOrMalformedTenant() {
    for (String tenant : List.of("", "invalid")) {
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().add("tenantId", tenant).build());
      assertThrows(ForbiddenException.class, () -> service.summary(filter));
    }
    auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().build());
    assertThrows(ForbiddenException.class, () -> service.summary(filter));
    verifyNoInteractions(invoices);
  }

  @Test void detailMapsItemsAndUsesScopedLookup() {
    when(invoices.findScopedInvoice(ID, List.of(TENANT), false)).thenReturn(Optional.of(invoice()));
    var result = service.detail(ID);
    assertEquals(ID, result.id());
    assertEquals("P1", result.items().get(0).productCode());
    assertEquals(new BigDecimal("20000"), result.totalAmount());
    verify(invoices, never()).findById(anyString());
  }

  @Test void missingAndOutOfScopeDetailsHaveSameNotFoundResponse() {
    when(invoices.findScopedInvoice(ID, List.of(TENANT), false)).thenReturn(Optional.empty());
    var ex = assertThrows(IllegalArgumentException.class, () -> service.detail(ID));
    assertEquals("Invoice not found", ex.getMessage());
    verify(invoices, never()).findById(anyString());
  }

  @Test void summaryKeepsCurrenciesSeparateAndUsesSameFilter() {
    var searched = new TransactionFilter(ID, "INV", Instant.EPOCH, Instant.now(), null);
    when(invoices.summarizeTransactions(List.of(TENANT), false, searched)).thenReturn(List.of(
        new CurrencyTotal("USD", 2, new BigDecimal("12.34")),
        new CurrencyTotal("VND", 3, new BigDecimal("45000"))));
    var result = service.summary(searched);
    assertEquals(5, result.totalTransactions());
    assertEquals(new BigDecimal("12.34"), result.totalsByCurrency().get(0).totalAmount());
    assertEquals(new BigDecimal("45000"), result.totalsByCurrency().get(1).totalAmount());
  }

  @Test void emptyScopeRemainsRestrictedAndSummaryIsZero() {
    when(acl.getMaxLevel()).thenReturn(RoleLevel.MASTER_TENANT);
    when(tenants.getScopedTenantIds(TENANT)).thenReturn(List.of());
    when(invoices.summarizeTransactions(List.of(), false, filter)).thenReturn(List.of());
    var result = service.summary(filter);
    assertEquals(0, result.totalTransactions());
    assertTrue(result.totalsByCurrency().isEmpty());
    verify(invoices).summarizeTransactions(List.of(), false, filter);
  }

  @Test void invalidPaginationAndIdentifierDoNotQueryStorage() {
    assertThrows(BadRequestException.class, () -> service.list(filter, -1, 20));
    assertThrows(BadRequestException.class, () -> service.list(filter, 0, 0));
    assertThrows(BadRequestException.class, () -> service.list(filter, 0, 101));
    assertThrows(BadRequestException.class, () -> service.detail("invalid"));
    verifyNoInteractions(invoices);
  }

  private Invoice invoice() {
    Invoice invoice = new Invoice();
    invoice.setId(ID); invoice.setTenantId(TENANT); invoice.setInvoiceNumber("INV-1");
    invoice.setTotalAmount(new BigDecimal("20000"));
    invoice.setItems(List.of(new InvoiceItemDto("P1", "Milk", new BigDecimal("2"),
        new BigDecimal("10000"), new BigDecimal("20000"))));
    return invoice;
  }
}
