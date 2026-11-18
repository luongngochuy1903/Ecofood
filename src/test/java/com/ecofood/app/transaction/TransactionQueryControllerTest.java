package com.ecofood.app.transaction;

import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.transaction.controller.TransactionController;
import com.ecofood.app.transaction.dto.*;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.app.transaction.service.TransactionQueryService;
import com.ecofood.app.transaction.service.TransactionService;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.entity.RoleLevel;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TransactionQueryControllerTest {
  private static final String TENANT = "000000000000000000000001";
  private static final String ID = "000000000000000000000002";
  private InvoiceRepository invoices;
  private ACLService acl;
  private MockMvc mvc;

  @BeforeEach void setup() {
    invoices = mock(InvoiceRepository.class);
    acl = mock(ACLService.class);
    when(acl.getMaxLevel()).thenReturn(RoleLevel.TENANT);
    var query = new TransactionQueryService(invoices, acl, mock(CoreTenantQueryClient.class));
    mvc = MockMvcBuilders.standaloneSetup(new TransactionController(mock(TransactionService.class), query))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test void listBindsAllFiltersAndReturnsPageEnvelope() throws Exception {
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_READ"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().add("tenantId", TENANT).build());
      when(invoices.findTransactions(eq(List.of(TENANT)), eq(false), any(), any()))
          .thenAnswer(call -> new PageImpl<>(List.of(), call.getArgument(3), 0));
      mvc.perform(get("/api/v1/transactions").param("invoiceId", ID)
              .param("invoiceNumber", "INV-1").param("status", "ACCEPTED")
              .param("from", "2026-01-01T00:00:00Z").param("to", "2026-01-31T23:59:59Z")
              .param("page", "2").param("size", "5"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2))
          .andExpect(jsonPath("$.size").value(5)).andExpect(jsonPath("$.content").isArray());
      var filter = org.mockito.ArgumentCaptor.forClass(TransactionFilter.class);
      verify(invoices).findTransactions(eq(List.of(TENANT)), eq(false), filter.capture(), any());
      assertEquals(ID, filter.getValue().invoiceId());
      assertEquals("INV-1", filter.getValue().invoiceNumber());
      assertEquals(Instant.parse("2026-01-01T00:00:00Z"), filter.getValue().from());
      assertEquals("ACCEPTED", filter.getValue().status().name());
    }
  }

  @Test void summaryUsesLiteralRouteAndReturnsZeroWithoutData() throws Exception {
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_READ"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().add("tenantId", TENANT).build());
      when(invoices.summarizeTransactions(eq(List.of(TENANT)), eq(false), any())).thenReturn(List.of());
      mvc.perform(get("/api/v1/transactions/summary"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalTransactions").value(0))
          .andExpect(jsonPath("$.totalsByCurrency").isEmpty());
      verify(invoices, never()).findScopedInvoice(anyString(), anyList(), anyBoolean());
    }
  }

  @Test void detailOutsideScopeReturns404() throws Exception {
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_READ"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().add("tenantId", TENANT).build());
      when(invoices.findScopedInvoice(ID, List.of(TENANT), false)).thenReturn(Optional.empty());
      mvc.perform(get("/api/v1/transactions/" + ID))
          .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Invoice not found"));
    }
  }

  @Test void everyReadRouteRequiresPermission() throws Exception {
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      for (String route : List.of("", "/summary", "/" + ID)) {
        mvc.perform(get("/api/v1/transactions" + route))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Missing TRANSACTION_READ permission"));
      }
      verifyNoInteractions(invoices, acl);
    }
  }

  @Test void invalidFilterTypesAndRangesReturn400() throws Exception {
    mvc.perform(get("/api/v1/transactions").param("status", "UNKNOWN")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/transactions").param("from", "not-a-date")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/transactions").param("page", "NaN")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/transactions").param("invoiceId", "invalid")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/transactions/summary")
            .param("from", "2026-02-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(invoices);
  }

  @Test void invalidPaginationReturns400() throws Exception {
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_READ"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().add("tenantId", TENANT).build());
      mvc.perform(get("/api/v1/transactions").param("page", "-1")).andExpect(status().isBadRequest());
      mvc.perform(get("/api/v1/transactions").param("size", "101")).andExpect(status().isBadRequest());
      verifyNoInteractions(invoices);
    }
  }

}
