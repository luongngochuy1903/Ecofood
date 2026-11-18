package com.ecofood.app.complaint;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.complaint.ComplaintIntakeResponses.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** HTTP binding, strict DTOs, sanitized errors and public limits; authorization tested separately. */
class ComplaintIntakeHttpTest {
  ComplaintIntakeService service; ComplaintPublicRateLimiter rates; MockMvc mvc;
  final String token = "a".repeat(43);
  final String issue = "\"requestId\":\"retry-1\",\"categoryCode\":\"SERVICE_ISSUE\",\"subject\":\"Missing service\",\"description\":\"Please review\"";
  @BeforeEach void setup() {
    service = mock(ComplaintIntakeService.class); rates = mock(ComplaintPublicRateLimiter.class);
    mvc = MockMvcBuilders.standaloneSetup(new ComplaintIntakeController(service)).setControllerAdvice(new ComplaintIntakeErrors())
        .addFilters(new ComplaintPublicRequestFilter(rates)).build();
  }
  @Test void publicBodyTokenAndCompatibilityAliasUseSameCoreIntakeAndNoStore() throws Exception {
    var response = new GuestConfirmation(new Confirmation("000000000000000000000031", "CMP-TEST", Status.SUBMITTED, Instant.now()), "https://portal.test/complaint#proof=x", Instant.now());
    when(service.guest(eq(token), any())).thenReturn(response);
    mvc.perform(post("/api/v1/public/invoice-complaints").contentType("application/json").content("{\"receiptToken\":\"" + token + "\"," + issue + "}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.complaint.status").value("SUBMITTED")).andExpect(header().string("Referrer-Policy", "no-referrer"));
    mvc.perform(post("/api/v1/public/receipts/" + token + "/complaints").contentType("application/json").content("{" + issue + "}"))
        .andExpect(status().isCreated()); verify(service, times(2)).guest(eq(token), any());
  }
  @Test void ownershipQueueStatusAndUnsupportedEvidenceCannotBeInjectedEvenWithNull() throws Exception {
    for (String field : new String[]{"tenantId", "customerId", "invoiceId", "distributionPartnerId", "currentQueueId", "status", "assigneeUserId", "evidenceIds"}) {
      mvc.perform(post("/api/v1/public/invoice-complaints").contentType("application/json")
          .content("{\"receiptToken\":\"" + token + "\"," + issue + ",\"" + field + "\":null}"))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }
  @Test void platformAndInternalBodiesRejectRoutingControlsAndOversizedOrBlankContent() throws Exception {
    for (String path : new String[]{"/api/v1/customers/me/complaints/platform", "/api/v1/complaints/internal"}) {
      mvc.perform(post(path).contentType("application/json").content("{" + issue + ",\"processingTenantId\":\"forged\"}"))
          .andExpect(status().isBadRequest());
      mvc.perform(post(path).contentType("application/json").content("{" + issue.replace("Please review", "a".repeat(10001)) + "}"))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }
  @Test void guestAndCustomerRequestsRejectAllEvidenceAndAttachmentFields() throws Exception {
    for (String path : new String[]{"/api/v1/public/invoice-complaints", "/api/v1/customers/me/complaints/invoice", "/api/v1/customers/me/complaints/platform"}) {
      String binding = path.contains("public") ? "\"receiptToken\":\"" + token + "\"," : path.endsWith("/invoice") ? "\"invoiceId\":\"000000000000000000000021\"," : "";
      for (String field : new String[]{"evidenceIds", "evidence", "attachments", "attachmentIds"})
        mvc.perform(post(path).contentType("application/json").content("{" + binding + issue + ",\"" + field + "\":[]}"))
            .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }
  @Test void publicRateLimitAndBodyLimitRejectBeforeIntakeAndNeverEchoSecret() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "private " + token)).when(rates).ip(any(), anyBoolean());
    mvc.perform(post("/api/v1/public/invoice-complaints").contentType("application/json").content("{}"))
        .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
    reset(rates);
    mvc.perform(post("/api/v1/public/invoice-complaints").contentType("application/json").content(" ".repeat(65537)))
        .andExpect(status().is(413)); verifyNoInteractions(service);
  }
  @Test void persistenceProviderErrorsDoNotExposePayloadOrCredentials() throws Exception {
    when(service.track(any())).thenThrow(new RuntimeException("secret=" + token));
    mvc.perform(post("/api/v1/public/complaints/track").contentType("application/json")
        .content("{\"complaintId\":\"000000000000000000000031\",\"accessSecret\":\"" + token + "\"}"))
        .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
  }
  @Test void publicHistoryPagingKeepsProofInBodyAndDelegatesToReporterScopedService() throws Exception {
    mvc.perform(post("/api/v1/public/complaints/track").param("historyPage", "1").param("historySize", "20").contentType("application/json")
        .content("{\"complaintId\":\"000000000000000000000031\",\"accessSecret\":\"" + token + "\"}"))
        .andExpect(status().isOk()); verify(service).track(any(), eq(1), eq(20));
  }
}
