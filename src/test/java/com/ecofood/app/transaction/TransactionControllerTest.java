package com.ecofood.app.transaction;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.transaction.controller.TransactionController;
import com.ecofood.app.transaction.service.TransactionService;
import com.ecofood.core.dto.SubmitTransactionResponse;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.security.AuthenticationContext;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransactionControllerTest {
  @Test
  void usesTokenScopeAndReturns201() throws Exception {
    var service = mock(TransactionService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new TransactionController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims)
          .thenReturn(Jwts.claims().subject("caller").add("tenantId", "tenant").build());
      when(service.submit(eq("tenant"), eq("caller"), eq("orch"), anyMap()))
          .thenReturn(
              new SubmitTransactionResponse(
                  "id", "INV1", TransactionStatus.ACCEPTED, Instant.now()));
      mvc.perform(
              post("/api/v1/sales/orch/transactions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("ACCEPTED"));
      verify(service).submit(eq("tenant"), eq("caller"), eq("orch"), anyMap());
    }
  }

  @Test
  void missingPermissionDeniesSubmissionAndReads() throws Exception {
    var service = mock(TransactionService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new TransactionController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of());
      mvc.perform(
              post("/api/v1/sales/orch/transactions")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isForbidden());
      mvc.perform(get("/api/v1/transactions")).andExpect(status().isForbidden());
      verifyNoInteractions(service);
    }
  }

  @Test
  void malformedJsonReturns400() throws Exception {
    var service = mock(TransactionService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new TransactionController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    mvc.perform(
            post("/api/v1/sales/orch/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
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
