package com.ecofood.app.customer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.config.*;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.customer.auth.CustomerIdentityService.CustomerSession;
import com.ecofood.app.customer.dto.*;
import com.ecofood.app.customer.repository.CustomerPurchaseRepository;
import com.ecofood.app.customer.service.CustomerPurchaseService;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class CustomerPurchaseServiceTest {
  CustomerIdentityService identities = mock(CustomerIdentityService.class);
  CustomerPurchaseRepository repository = mock(CustomerPurchaseRepository.class);
  MongoTemplate mongo = mock(MongoTemplate.class);
  CustomerPurchaseService service = new CustomerPurchaseService(identities, repository);
  CustomerSession scope = new CustomerSession("000000000000000000000001", "000000000000000000000002");
  CustomerPurchaseFilter filter = new CustomerPurchaseFilter(null, null, null, null, null);
  Invoice invoice;

  @BeforeEach void setup() {
    when(identities.requirePurchaseSession()).thenReturn(scope);
    invoice = new Invoice(); invoice.setId("000000000000000000000003"); invoice.setTenantId("000000000000000000000004");
    invoice.setDistributionPartnerId("SHOP-A"); invoice.setInvoiceNumber("INV-A"); invoice.setCurrency("USD");
    invoice.setTransactionTime(Instant.parse("2026-11-15T00:00:00Z")); invoice.setStatus(TransactionStatus.ACCEPTED);
    invoice.setTotalAmount(new BigDecimal("0.30")); invoice.setItems(List.of(new InvoiceItemDto("P1", "Milk", new BigDecimal("3"), new BigDecimal("0.10"), new BigDecimal("0.30"))));
  }

  @Test void listUsesCustomerScopeAndStablePagingWithoutReturningInternalInvoiceFields() {
    when(repository.list(eq(scope), eq(filter), any())).thenReturn(new PageImpl<>(List.of(invoice), PageRequest.of(1, 10), 11));
    var page = service.list(filter, 1, 10, "transactionTime", "desc");
    assertEquals(11, page.getTotalElements()); assertEquals(1, page.getPage());
    var row = page.getContent().get(0); assertEquals("INV-A", row.invoiceCode()); assertEquals("SHOP-A", row.distributionPartnerName());
    assertEquals(List.of(), row.items()); assertEquals(1, row.itemCount());
    var pageable = ArgumentCaptor.forClass(Pageable.class); verify(repository).list(eq(scope), eq(filter), pageable.capture());
    assertEquals(10, pageable.getValue().getOffset()); assertNotNull(pageable.getValue().getSort().getOrderFor("_id"));
  }

  @Test void detailUsesActualInvoiceAmountsWithoutLegacyDependency() {
    when(repository.detail(scope, invoice.getId())).thenReturn(Optional.of(invoice));
    var result = service.detail(invoice.getId());
    assertEquals(new BigDecimal("0.30"), result.subtotal()); assertEquals(invoice.getTotalAmount(), result.totalAmount());
    assertEquals(new BigDecimal("3"), result.items().get(0).quantity()); assertEquals(new BigDecimal("0.10"), result.items().get(0).unitPrice());
    verifyNoInteractions(mongo);
  }

  @Test void unownedMissingOrBrokenLinkNeverReturnsInvoice() {
    when(repository.detail(scope, invoice.getId())).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.detail(invoice.getId())); verifyNoInteractions(mongo);
  }

  @Test void statisticsKeepCurrenciesSeparateAndForwardAllFilters() {
    var selected = new CustomerPurchaseFilter("INV", invoice.getTransactionTime(), invoice.getTransactionTime(), "SHOP-A", TransactionStatus.ACCEPTED);
    var expected = new CustomerStatisticsResponse(3, List.of(new CustomerStatisticsResponse.CurrencySpend("USD", 2, new BigDecimal("0.30")),
        new CustomerStatisticsResponse.CurrencySpend("VND", 1, new BigDecimal("50000"))));
    when(repository.statistics(scope, selected, java.time.ZoneId.of("UTC"))).thenReturn(expected);
    assertEquals(expected, service.statistics(selected)); verify(repository).statistics(scope, selected, java.time.ZoneId.of("UTC"));
  }

  @Test void invalidPagingSortingAndDatesAreRejected() {
    assertThrows(BadRequestException.class, () -> service.list(filter, -1, 10, "transactionTime", "desc"));
    assertThrows(BadRequestException.class, () -> service.list(filter, 0, 101, "transactionTime", "desc"));
    assertThrows(BadRequestException.class, () -> service.list(filter, 0, 10, "tenantId", "desc"));
    assertThrows(BadRequestException.class, () -> service.list(filter, 0, 10, "transactionTime", "invalid"));
    assertThrows(BadRequestException.class, () -> service.detail("bad"));
    assertThrows(BadRequestException.class, () -> new CustomerPurchaseFilter(null, Instant.MAX, Instant.MIN, null, null));
    assertThrows(BadRequestException.class, () -> new CustomerPurchaseFilter("a".repeat(101), null, null, null, null));
    verifyNoInteractions(repository);
  }

  @Test void statisticsValidatesTimezoneBeforeQueryAndForwardsAnExplicitZone() {
    assertThrows(BadRequestException.class, () -> service.statistics(filter, "not/a-zone"));
    verifyNoInteractions(repository);
    service.statistics(filter, "Asia/Ho_Chi_Minh");
    verify(repository).statistics(scope, filter, java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
  }

  @Test void invalidCustomerSessionIsRejectedBeforeReadingAnyPurchase() {
    when(identities.requirePurchaseSession()).thenThrow(new ForbiddenException("Denied"));
    assertThrows(ForbiddenException.class, () -> service.list(filter, 0, 10, "transactionTime", "desc"));
    assertThrows(ForbiddenException.class, () -> service.detail(invoice.getId()));
    assertThrows(ForbiddenException.class, () -> service.statistics(filter)); verifyNoInteractions(repository, mongo);
  }
}
