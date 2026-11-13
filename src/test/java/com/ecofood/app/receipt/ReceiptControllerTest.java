package com.ecofood.app.receipt;

import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.receipt.controller.*;
import com.ecofood.app.receipt.dto.*;
import com.ecofood.app.receipt.service.ReceiptService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReceiptControllerTest {
  private final ReceiptService service = mock(ReceiptService.class);
  private MockMvc mvc;
  private static final String ID = "abcdef000000000000000001";
  private static final String URL = "/api/v1/transactions/" + ID + "/receipts";

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(new ReceiptController(service), new PublicReceiptController(service))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test void issueReturnsContractAndNoStore() throws Exception {
    when(service.issue(eq(ID), any())).thenReturn(new IssueReceiptResponse("receipt", ID,
        "https://receipts.example/api/v1/public/receipts?token=opaque", Instant.parse("2026-11-09T00:00:00Z")));
    mvc.perform(post(URL).contentType("application/json")
            .content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\"}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.receiptUrl").value("https://receipts.example/api/v1/public/receipts?token=opaque"))
        .andExpect(jsonPath("$.invoiceId").value(ID)).andExpect(jsonPath("$.expiresAt").exists());
  }

  @Test void requestIdIsRequiredAndMustBeUuid() throws Exception {
    mvc.perform(post(URL).contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post(URL).contentType("application/json").content("{\"requestId\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test void guestResponseExposesOnlyExplicitReceiptFields() throws Exception {
    when(service.read("opaque")).thenReturn(new PublicReceiptResponse("INV-1", Instant.parse("2026-11-08T00:00:00Z"),
        "DP-1", "VND", new BigDecimal("20000"), List.of(new PublicReceiptResponse.Item("P1", "Milk",
        new BigDecimal("2"), new BigDecimal("10000"), new BigDecimal("20000")))));
    mvc.perform(get("/api/v1/public/receipts").param("token", "opaque"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(jsonPath("$.invoiceNumber").value("INV-1"))
        .andExpect(jsonPath("$.distributionPartner").value("DP-1"))
        .andExpect(jsonPath("$.items[0].quantity").value(2))
        .andExpect(jsonPath("$.tenantId").doesNotExist()).andExpect(jsonPath("$.posId").doesNotExist())
        .andExpect(jsonPath("$.submittedBy").doesNotExist()).andExpect(jsonPath("$.invoiceId").doesNotExist())
        .andExpect(jsonPath("$.planId").doesNotExist()).andExpect(jsonPath("$.tokenHash").doesNotExist())
        .andExpect(jsonPath("$.receiptId").doesNotExist()).andExpect(jsonPath("$.issuedBy").doesNotExist());
  }

  @Test void unavailableTokenHasGeneric404EvenWhenMissing() throws Exception {
    when(service.read(anyString())).thenThrow(new IllegalArgumentException("Receipt unavailable"));
    mvc.perform(get("/api/v1/public/receipts")).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Receipt unavailable"));
  }

  @Test void revokeReturns204WithoutTokenOrInvoiceData() throws Exception {
    mvc.perform(delete(URL + "/" + "a".repeat(64))).andExpect(status().isNoContent())
        .andExpect(content().string(""));
    verify(service).revoke(ID, "a".repeat(64));
  }
}
