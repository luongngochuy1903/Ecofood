package com.ecofood.app.customer;

import static com.ecofood.app.customer.CustomerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.customer.controller.CustomerController;
import com.ecofood.app.customer.controller.CustomerReceiptController;
import com.ecofood.app.customer.exception.CustomerApiErrors;
import com.ecofood.app.customer.security.CustomerAccess;
import com.ecofood.app.customer.service.CustomerReceiptServiceImpl;
import com.ecofood.app.customer.service.CustomerServiceImpl;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.dto.CustomerIdentityDtos;
import com.ecofood.core.dto.CustomerReceiptDtos;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CustomerApiTest {
  @Test void invoiceResponseOnlyContainsCustomerSafeFields() throws Exception {
    var access = mock(CustomerAccess.class); when(access.current()).thenReturn(actor(A)); var db = new AtomicCustomerMongo();
    var invoice = invoice(); invoice.setInvoiceHolder(new Invoice.InvoiceHolder(A, A)); invoice.setOrchestratorPayload(Map.of("secret", "POS-only")); invoice.setPostWorkflowResults(Map.of("SEND_EMAIL_NOTIFICATION", "PENDING")); db.insert(invoice);
    var tenants=mock(com.ecofood.core.client.CoreTenantQueryClient.class);
    when(tenants.propositionReference(TENANT)).thenReturn(new com.ecofood.core.dto.PropositionTenantReference(TENANT,"Submitting tenant",null,
        com.ecofood.core.enums.PartnerTypeEnum.BRAND,com.ecofood.core.enums.TenantTypeEnum.STANDALONE,List.of(),List.of(),false));
    var service = new CustomerServiceImpl(access, db.mongo, tenants);
    var mvc = MockMvcBuilders.standaloneSetup(new CustomerController(service, service)).setControllerAdvice(new CustomerApiErrors(), new GlobalExceptionHandler()).build();
    mvc.perform(get("/api/v1/customers/me/invoices/" + INVOICE).header("X-Tenant-Id", OTHER_TENANT))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.invoiceNumber").value("INV-001")).andExpect(jsonPath("$.totalAmount").value("20"))
        .andExpect(jsonPath("$.tenantId").value(TENANT)).andExpect(jsonPath("$.tenantName").value("Submitting tenant"))
        .andExpect(jsonPath("$.snapshot").doesNotExist()).andExpect(jsonPath("$.posId").doesNotExist()).andExpect(jsonPath("$.submittedBy").doesNotExist()).andExpect(jsonPath("$.orchestratorPayload").doesNotExist());
    when(access.current()).thenReturn(actor(B)); mvc.perform(get("/api/v1/customers/me/invoices/" + INVOICE)).andExpect(status().isNotFound());
    verify(tenants,times(1)).propositionReference(TENANT);
  }
  @Test void profileValidationCannotChangeEmailCategoryOrCustomerId() throws Exception {
    var service = mock(CustomerServiceImpl.class); var mvc = MockMvcBuilders.standaloneSetup(new CustomerController(service, service)).setControllerAdvice(new CustomerApiErrors(), new GlobalExceptionHandler()).build();
    mvc.perform(put("/api/v1/customers/me").contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"\",\"lastName\":\"Last\",\"revision\":0}")).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
  @Test void invoiceIdAloneDoesNotSatisfyClaimContractAndCallerIdentityIsServerResolved() throws Exception {
    var service = mock(CustomerReceiptServiceImpl.class); when(service.claim(null)).thenThrow(CustomerServiceImpl.unavailable());
    var mvc = MockMvcBuilders.standaloneSetup(new CustomerReceiptController(service)).setControllerAdvice(new CustomerApiErrors(), new GlobalExceptionHandler()).build();
    mvc.perform(post("/api/v1/customers/me/invoices/claim").contentType(MediaType.APPLICATION_JSON).content("{\"invoiceId\":\"" + INVOICE + "\",\"customerId\":\"" + B + "\"}")).andExpect(status().isNotFound());
    verify(service).claim(null);
  }
  @Test void publicReceiptResponseDoesNotExposeInternalIdentityOrOwner() throws Exception {
    var service = mock(CustomerReceiptServiceImpl.class);
    when(service.view("t".repeat(43))).thenReturn(new CustomerReceiptDtos.GuestInvoice("INV-001", null, "USD", BigDecimal.TEN, List.of(), new CustomerIdentityDtos.LoginConfig(TENANT, "realm", "client", false)));
    var mvc = MockMvcBuilders.standaloneSetup(new CustomerReceiptController(service)).setControllerAdvice(new CustomerApiErrors(), new GlobalExceptionHandler()).build();
    mvc.perform(post("/api/v1/auth/customer-receipts/view").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + "t".repeat(43) + "\"}"))
        .andExpect(status().isOk()).andExpect(header().string("Referrer-Policy", "no-referrer")).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.invoiceHolder").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist());
  }
}
