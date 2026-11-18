package com.ecofood.app.transaction;

import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.transaction.controller.TransactionController;
import com.ecofood.app.transaction.service.TransactionService;
import com.ecofood.app.transaction.service.TransactionQueryService;
import com.ecofood.core.dto.SubmitTransactionResponse;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.security.AuthenticationContext;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP contract tests; existing InternalJwtRequestFilterTest covers JWT verification. */
class TransactionControllerTest {
  private TransactionService service;
  private MockMvc mvc;
  private static final String BODY = """
      {"invoiceNumber":"INV1","transactionTime":"2026-01-01T00:00:00Z",
       "currency":"VND","totalAmount":20000,
       "items":[{"productCode":"P1","productName":"Milk","quantity":2,
                 "unitPrice":10000,"amount":20000}]}
      """;

  @BeforeEach void setup() {
    service = mock(TransactionService.class);
    mvc = MockMvcBuilders.standaloneSetup(new TransactionController(service, mock(TransactionQueryService.class)))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test void returns201AndUsesIdentityFromVerifiedContext() throws Exception {
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("pos-user").add("tenantId", "tenant").build());
      when(service.submit(eq("tenant"), eq("pos-user"), any())).thenReturn(
          new SubmitTransactionResponse("id", "INV1", TransactionStatus.ACCEPTED, Instant.now()));
      mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACCEPTED"))
          .andExpect(jsonPath("$.invoiceNumber").value("INV1"));
      verify(service).submit(eq("tenant"), eq("pos-user"), any());
    }
  }

  @Test void rejectsMissingPermission() throws Exception {
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of());
      mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isForbidden());
      verifyNoInteractions(service);
    }
  }

  @Test void rejectsMissingTenant() throws Exception {
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("pos-user").build());
      mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isForbidden());
      verifyNoInteractions(service);
    }
  }

  @Test void rejectsInvalidNestedPayloadBeforeProcessing() throws Exception {
    mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON)
        .content(BODY.replace("\"quantity\":2", "\"quantity\":0")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test void duplicateIs409() throws Exception {
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("pos-user").add("tenantId", "tenant").build());
      when(service.submit(any(), any(), any())).thenThrow(new IllegalStateException("Duplicate transaction"));
      mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Duplicate transaction"));
    }
  }
}
