package com.ecofood.app.transaction;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.transaction.controller.BatchTransactionController;
import com.ecofood.app.transaction.service.BatchTransactionService;
import com.ecofood.app.transaction.dto.BatchTransactionDtos.*;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.metrics.service.KpiProcessingException;
import com.ecofood.core.security.AuthenticationContext;
import io.jsonwebtoken.Jwts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BatchTransactionControllerTest {
  private static final String BODY="""
      {"transaction":{"invoices":[{"invoiceNumber":"INV-1","items":[{"lineId":"1","orchestratorId":"orch","planId":"plan","quantity":1,"unitPrice":50000,"amount":50000}]}]}}
      """;
  @Test void missingPermissionCannotSubmitAndIdentityComesFromInternalToken() throws Exception {
    var service=mock(BatchTransactionService.class);
    var mvc=MockMvcBuilders.standaloneSetup(new BatchTransactionController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    try(var auth=mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of());
      mvc.perform(post("/api/v1/sales/transaction-batches").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());verifyNoInteractions(service);
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("seller").add("tenantId","tenant").build());
      when(service.submit(eq("tenant"),eq("seller"),any())).thenReturn(new Response(new Result(List.of())));
      mvc.perform(post("/api/v1/sales/transaction-batches").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.transaction.invoices").isArray());
      verify(service).submit(eq("tenant"),eq("seller"),any());
    }
  }
  @Test void fractionalPlanQuantityIsNotSilentlyTruncated() throws Exception {
    var service=mock(BatchTransactionService.class);
    var mvc=MockMvcBuilders.standaloneSetup(new BatchTransactionController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    mvc.perform(post("/api/v1/sales/transaction-batches").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"quantity\":1","\"quantity\":1.5")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
  @Test void unsupportedMongoTransactionsReturn503WithoutFalseConfirmation() throws Exception {
    var service=mock(BatchTransactionService.class);
    var mvc=MockMvcBuilders.standaloneSetup(new BatchTransactionController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    try(var auth=mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("seller").add("tenantId","tenant").build());
      when(service.submit(anyString(),anyString(),any())).thenThrow(new KpiProcessingException("MONGO_TRANSACTIONS_UNSUPPORTED",true));
      mvc.perform(post("/api/v1/sales/transaction-batches").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MONGO_TRANSACTIONS_UNSUPPORTED"));
    }
  }
}
