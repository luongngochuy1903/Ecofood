package com.ecofood.app.transaction;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.transaction.controller.TransactionController;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.service.TransactionService;
import com.ecofood.core.security.AuthenticationContext;
import io.jsonwebtoken.Jwts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransactionControllerTest {
  @Test
  void usesTokenScopeToReadInvoiceListAndDetail() throws Exception {
    var service = mock(TransactionService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new TransactionController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_READ"));
      auth.when(AuthenticationContext::getClaims)
          .thenReturn(Jwts.claims().subject("caller").add("tenantId", "tenant").build());
      var invoice = new Invoice();
      invoice.setId("invoice");
      when(service.list(eq("tenant"), eq(0), eq(20)))
          .thenReturn(new PageImpl<>(List.of(invoice), PageRequest.of(0, 20), 1));
      mvc.perform(get("/api/v1/transactions"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].id").value("invoice"));
      when(service.detail(eq("tenant"), eq("invoice"))).thenReturn(invoice);
      mvc.perform(get("/api/v1/transactions/invoice"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value("invoice"));
      verify(service).list(eq("tenant"), eq(0), eq(20));
      verify(service).detail(eq("tenant"), eq("invoice"));
    }
  }

  @Test
  void missingPermissionDeniesReads() throws Exception {
    var service = mock(TransactionService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new TransactionController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of());
      mvc.perform(get("/api/v1/transactions")).andExpect(status().isForbidden());
      mvc.perform(get("/api/v1/transactions/invoice")).andExpect(status().isForbidden());
      verifyNoInteractions(service);
    }
  }

  @Test
  void invalidPaginationReturns400() throws Exception {
    var service = mock(TransactionService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new TransactionController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    mvc.perform(get("/api/v1/transactions").param("page", "invalid"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
