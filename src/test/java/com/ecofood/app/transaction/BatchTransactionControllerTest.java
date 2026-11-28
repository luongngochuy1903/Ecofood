package com.ecofood.app.transaction;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.transaction.controller.BatchTransactionController;
import com.ecofood.app.transaction.csv.BatchTransactionCsvParser;
import com.ecofood.app.transaction.service.BatchTransactionService;
import com.ecofood.app.transaction.dto.BatchTransactionDtos.*;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.metrics.service.KpiProcessingException;
import com.ecofood.core.security.AuthenticationContext;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BatchTransactionControllerTest {
  private static final String BODY="""
      {"transaction":{"invoices":[{"items":[{"planId":"plan","quantity":1,"actualPrice":50000}]}]}}
      """;
  private BatchTransactionController controller(BatchTransactionService service) {
    return new BatchTransactionController(service, new BatchTransactionCsvParser());
  }
  @Test void missingPermissionCannotSubmitAndIdentityComesFromInternalToken() throws Exception {
    var service=mock(BatchTransactionService.class);
    var mvc=MockMvcBuilders.standaloneSetup(controller(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    try(var auth=mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of());
      mvc.perform(post("/api/v1/sales/transactions").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());verifyNoInteractions(service);
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("seller").add("tenantId","tenant").build());
      when(service.submit(eq("tenant"),eq("seller"),any())).thenReturn(new Response(new Result(List.of())));
      mvc.perform(post("/api/v1/sales/transactions").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.transaction.invoices").isArray());
      verify(service).submit(eq("tenant"),eq("seller"),any());
    }
  }
  @Test void fractionalPlanQuantityIsNotSilentlyTruncated() throws Exception {
    var service=mock(BatchTransactionService.class);
    var mvc=MockMvcBuilders.standaloneSetup(controller(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    mvc.perform(post("/api/v1/sales/transactions").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"quantity\":1","\"quantity\":1.5")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
  @Test void unsupportedMongoTransactionsReturn503WithoutFalseConfirmation() throws Exception {
    var service=mock(BatchTransactionService.class);
    var mvc=MockMvcBuilders.standaloneSetup(controller(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    try(var auth=mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("seller").add("tenantId","tenant").build());
      when(service.submit(anyString(),anyString(),any())).thenThrow(new KpiProcessingException("MONGO_TRANSACTIONS_UNSUPPORTED",true));
      mvc.perform(post("/api/v1/sales/transactions").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MONGO_TRANSACTIONS_UNSUPPORTED"));
    }
  }
  @Test void csvUploadParsesIntoTheSameRequestShapeAndDelegatesToTheSameSubmit() throws Exception {
    var service=mock(BatchTransactionService.class);
    var mvc=MockMvcBuilders.standaloneSetup(controller(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    String csv = """
        invoiceRef,planId,quantity,actualPrice,invoiceHolder.email,item.cashierId
        1,plan-abc,2,15.00,a@x.com,C-01
        1,plan-def,1,,a@x.com,C-01
        2,plan-ghi,3,9.50,b@y.com,
        """;
    var file = new MockMultipartFile("file", "batch.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    try(var auth=mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("seller").add("tenantId","tenant").build());
      when(service.submit(eq("tenant"),eq("seller"),any())).thenReturn(new Response(new Result(List.of())));
      mvc.perform(multipart("/api/v1/sales/transactions/csv").file(file)).andExpect(status().isOk());
      var captor = org.mockito.ArgumentCaptor.forClass(Request.class);
      verify(service).submit(eq("tenant"),eq("seller"),captor.capture());
      var invoices = captor.getValue().transaction().invoices();
      org.assertj.core.api.Assertions.assertThat(invoices).hasSize(2);
      org.assertj.core.api.Assertions.assertThat(invoices.get(0).items()).hasSize(2);
      org.assertj.core.api.Assertions.assertThat(invoices.get(0).invoiceHolder()).containsEntry("email","a@x.com");
      org.assertj.core.api.Assertions.assertThat(invoices.get(0).items().get(0).additionalInfo()).containsEntry("cashierId","C-01");
      org.assertj.core.api.Assertions.assertThat(invoices.get(1).items()).hasSize(1);
      org.assertj.core.api.Assertions.assertThat(invoices.get(1).invoiceHolder()).containsEntry("email","b@y.com");
    }
  }
  @Test void csvUploadRejectsInconsistentInvoiceHolderWithinTheSameInvoiceRef() throws Exception {
    var service=mock(BatchTransactionService.class);
    var mvc=MockMvcBuilders.standaloneSetup(controller(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    String csv = """
        invoiceRef,planId,quantity,invoiceHolder.email
        1,plan-abc,2,a@x.com
        1,plan-def,1,different@x.com
        """;
    var file = new MockMultipartFile("file", "batch.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    try(var auth=mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getPermissions).thenReturn(List.of("TRANSACTION_SUBMIT"));
      auth.when(AuthenticationContext::getClaims).thenReturn(Jwts.claims().subject("seller").add("tenantId","tenant").build());
      mvc.perform(multipart("/api/v1/sales/transactions/csv").file(file)).andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }
}
