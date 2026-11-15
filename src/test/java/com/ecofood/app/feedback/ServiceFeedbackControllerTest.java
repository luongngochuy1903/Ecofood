package com.ecofood.app.feedback;

import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.feedback.controller.ServiceFeedbackController;
import com.ecofood.app.feedback.repository.ServiceFeedbackRepository;
import com.ecofood.app.feedback.service.ServiceFeedbackService;
import com.ecofood.app.receipt.service.ReceiptService;
import com.ecofood.app.transaction.entity.Invoice;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ServiceFeedbackControllerTest {
  private static final String BASE = "/api/v1/public/receipts/" + "a".repeat(43);
  private final ReceiptService receipts = mock(ReceiptService.class);
  private final ServiceFeedbackRepository feedbacks = mock(ServiceFeedbackRepository.class);
  private ValidatorFactory factory;
  private MockMvc mvc;

  @BeforeEach void setup() {
    factory = Validation.buildDefaultValidatorFactory();
    var invoice = new Invoice(); invoice.setId("invoice"); invoice.setTenantId("tenant"); invoice.setDistributionPartnerId("partner");
    when(receipts.requireValidInvoice("a".repeat(43))).thenReturn(invoice);
    mvc = MockMvcBuilders.standaloneSetup(new ServiceFeedbackController(
        new ServiceFeedbackService(receipts, feedbacks, factory.getValidator())))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }
  @AfterEach void close() { factory.close(); }

  @Test void validFeedbackReturns201MinimalAckAndNoStore() throws Exception {
    mvc.perform(post(BASE + "/feedback").contentType("application/json").content("{\"rating\":5}"))
        .andExpect(status().isCreated()).andExpect(content().json("{\"submitted\":true}"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(jsonPath("$.comment").doesNotExist()).andExpect(jsonPath("$.invoiceId").doesNotExist());
  }

  @Test void statusRevealsOnlyBooleanWithoutContentOrPersonalData() throws Exception {
    when(feedbacks.existsByTenantIdAndInvoiceId("tenant", "invoice")).thenReturn(true);
    mvc.perform(get(BASE + "/feedback-status")).andExpect(status().isOk())
        .andExpect(content().json("{\"submitted\":true}"))
        .andExpect(jsonPath("$.rating").doesNotExist()).andExpect(jsonPath("$.comment").doesNotExist())
        .andExpect(jsonPath("$.feedbackId").doesNotExist()).andExpect(jsonPath("$.tenantId").doesNotExist());
  }

  @Test void rejectsInvalidPayloadIncludingFractionalStarsAndLongComment() throws Exception {
    for (String body : new String[] {"{}", "{\"rating\":null}", "{\"rating\":0}", "{\"rating\":6}",
        "{\"rating\":1.5}", "{\"rating\":\"bad\"}", "{\"rating\":5,\"comment\":\"" + "x".repeat(2001) + "\"}"}) {
      mvc.perform(post(BASE + "/feedback").contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }
    verifyNoInteractions(feedbacks);
  }

  @Test void explicitlyRejectsOwnershipStatusAndIdFieldsInRequestBody() throws Exception {
    for (String field : new String[] {"invoiceId", "tenantId", "distributionPartnerId", "feedbackId", "status", "createdAt", "deleted"}) {
      mvc.perform(post(BASE + "/feedback").contentType("application/json")
              .content("{\"rating\":5,\"" + field + "\":\"override\"}"))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(feedbacks);
  }

  @Test void duplicateReturns409AndDoesNotReplacePreviousFeedback() throws Exception {
    when(feedbacks.existsByTenantIdAndInvoiceId("tenant", "invoice")).thenReturn(true);
    mvc.perform(post(BASE + "/feedback").contentType("application/json").content("{\"rating\":1}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Service feedback already submitted"));
  }

  @Test void storageErrorReturns500WithoutExposingCommentTokenOrDatabaseError() throws Exception {
    when(feedbacks.insert(any(com.ecofood.app.feedback.entity.ServiceFeedback.class)))
        .thenThrow(new DataAccessResourceFailureException("private database details"));
    mvc.perform(post(BASE + "/feedback").contentType("application/json").content("{\"rating\":5}"))
        .andExpect(status().isInternalServerError()).andExpect(content().json("{\"message\":\"An unexpected error occurred\"}"));
  }
}
