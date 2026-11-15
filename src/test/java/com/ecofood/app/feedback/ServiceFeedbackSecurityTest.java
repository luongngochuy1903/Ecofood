package com.ecofood.app.feedback;

import com.ecofood.app.auth.security.*;
import com.ecofood.app.auth.service.InternalTokenService;
import com.ecofood.app.config.*;
import com.ecofood.app.feedback.controller.ServiceFeedbackController;
import com.ecofood.app.feedback.entity.ServiceFeedback;
import com.ecofood.app.feedback.repository.ServiceFeedbackRepository;
import com.ecofood.app.feedback.service.ServiceFeedbackService;
import com.ecofood.app.receipt.config.ReceiptProperties;
import com.ecofood.app.receipt.entity.ReceiptAccess;
import com.ecofood.app.receipt.repository.ReceiptAccessRepository;
import com.ecofood.app.receipt.service.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.service.SaleOrchestratorConfigProvider;
import jakarta.validation.*;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles("feedback-security-test")
@ContextConfiguration(classes = ServiceFeedbackSecurityTest.Config.class)
@TestPropertySource(properties = {"jwt.secret=dGVzdC1vbmx5LWZlZWRiYWNrLXNlY3VyaXR5LXRlc3Qta2V5", "jwt.issuer=app", "jwt.audience=app-api",
    "jwt.tokenValidityInSeconds=1800", "jwt.refreshTokenValidityInSeconds=86400"})
class ServiceFeedbackSecurityTest {
  private static final String TOKEN = "a".repeat(43);
  private static final String BASE = "/api/v1/public/receipts/" + TOKEN;
  private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
  @Autowired WebApplicationContext context;
  @Autowired InvoiceRepository invoices;
  @Autowired ReceiptAccessRepository receiptRepo;
  @Autowired ServiceFeedbackRepository feedbacks;
  @Autowired InternalTokenService internalTokens;
  @Autowired ReceiptTokenCodec codec;
  private MockMvc mvc;
  private ReceiptAccess access;
  private Invoice invoice;
  private AtomicReference<ServiceFeedback> stored;

  @BeforeEach void setup() {
    reset(invoices, receiptRepo, feedbacks, internalTokens);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    stored = new AtomicReference<>();
    access = new ReceiptAccess(); access.setInvoiceId("invoice-a"); access.setTenantId("tenant-a");
    access.setPosId("pos-a"); access.setDistributionPartnerId("partner-a"); access.setExpiresAt(NOW.plusSeconds(3600));
    when(receiptRepo.findByTokenHashAndRevokedAtIsNullAndExpiresAtGreaterThan(eq(codec.hash(TOKEN)), eq(NOW)))
        .thenAnswer(call -> access.getRevokedAt() == null && access.getExpiresAt().isAfter(NOW) ? Optional.of(access) : Optional.empty());
    invoice = new Invoice(); invoice.setId("invoice-a"); invoice.setTenantId("tenant-a");
    invoice.setDistributionPartnerId("partner-a"); invoice.setStatus(TransactionStatus.ACCEPTED);
    when(invoices.findByIdAndTenantIdAndPosIdAndDistributionPartnerId("invoice-a", "tenant-a", "pos-a", "partner-a"))
        .thenReturn(Optional.of(invoice));
    when(feedbacks.existsByTenantIdAndInvoiceId(anyString(), anyString())).thenAnswer(call -> stored.get() != null
        && stored.get().getTenantId().equals(call.getArgument(0)) && stored.get().getInvoiceId().equals(call.getArgument(1)));
    when(feedbacks.insert(any(ServiceFeedback.class))).thenAnswer(call -> {
      ServiceFeedback value = call.getArgument(0);
      if (!stored.compareAndSet(null, value)) throw new DuplicateKeyException("unique invoice");
      return value;
    });
  }

  @Test void guestCanSubmitAndReadMinimalStatusWithoutCustomerAccount() throws Exception {
    mvc.perform(get(BASE + "/feedback-status")).andExpect(status().isOk()).andExpect(content().json("{\"submitted\":false}"));
    mvc.perform(post(BASE + "/feedback").contentType("application/json").content("{\"rating\":5}"))
        .andExpect(status().isCreated()).andExpect(content().json("{\"submitted\":true}"));
    mvc.perform(get(BASE + "/feedback-status")).andExpect(status().isOk()).andExpect(content().json("{\"submitted\":true}"))
        .andExpect(jsonPath("$.comment").doesNotExist()).andExpect(jsonPath("$.rating").doesNotExist());
    verifyNoInteractions(internalTokens);
    verify(invoices, never()).save(any(Invoice.class)); verify(invoices, never()).insert(any(Invoice.class));
  }

  @Test void unrelatedExpiredJwtDoesNotBlockReceiptAuthenticatedFeedback() throws Exception {
    mvc.perform(post(BASE + "/feedback").header("Authorization", "Bearer expired-jwt")
            .contentType("application/json").content("{\"rating\":1,\"comment\":\"Slow service\"}"))
        .andExpect(status().isCreated());
    mvc.perform(get(BASE + "/feedback-status").header("Authorization", "Bearer expired-jwt")).andExpect(status().isOk());
    verifyNoInteractions(internalTokens);
  }

  @Test void invalidExpiredOrRevokedTokenCannotSubmitOrCheckStatus() throws Exception {
    mvc.perform(post("/api/v1/public/receipts/bad/feedback").contentType("application/json").content("{\"rating\":5}"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Receipt unavailable"));
    access.setExpiresAt(NOW);
    mvc.perform(post(BASE + "/feedback").contentType("application/json").content("{\"rating\":5}"))
        .andExpect(status().isNotFound());
    access.setExpiresAt(NOW.plusSeconds(60)); access.setRevokedAt(NOW);
    mvc.perform(get(BASE + "/feedback-status")).andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    verifyNoInteractions(feedbacks);
  }

  @Test void receiptBindingCannotBeUsedToReadAnInvoiceUnderAnotherTenantOrPartner() throws Exception {
    access.setTenantId("tenant-b");
    mvc.perform(post(BASE + "/feedback").contentType("application/json").content("{\"rating\":5}"))
        .andExpect(status().isNotFound());
    access.setTenantId("tenant-a"); access.setDistributionPartnerId("partner-b");
    mvc.perform(get(BASE + "/feedback-status")).andExpect(status().isNotFound());
    verifyNoInteractions(feedbacks);
  }

  @Test void ineligibleInvoiceIsRejectedAndPreviouslySubmittedContentIsNotExposedOnRetry() throws Exception {
    invoice.setStatus(null);
    mvc.perform(post(BASE + "/feedback").contentType("application/json").content("{\"rating\":5}"))
        .andExpect(status().isNotFound());
    invoice.setStatus(TransactionStatus.ACCEPTED);
    mvc.perform(post(BASE + "/feedback").contentType("application/json").content("{\"rating\":5,\"comment\":\"Private comment\"}"))
        .andExpect(status().isCreated());
    mvc.perform(post(BASE + "/feedback").contentType("application/json").content("{\"rating\":1}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.rating").doesNotExist()).andExpect(jsonPath("$.comment").doesNotExist());
  }

  @Test void onlyExactFeedbackOperationsArePublicAndManagementRemainsProtected() throws Exception {
    mvc.perform(get(BASE + "/feedback")).andExpect(status().isUnauthorized());
    mvc.perform(post(BASE + "/feedback-status")).andExpect(status().isUnauthorized());
    mvc.perform(put(BASE + "/feedback")).andExpect(status().isUnauthorized());
    mvc.perform(get(BASE + "/feedback-status/extra")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/transactions")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/transactions/abc/receipts")).andExpect(status().isUnauthorized());
    verifyNoInteractions(feedbacks);
  }

  @Configuration
  @Profile("feedback-security-test")
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfig.class, GlobalExceptionHandler.class})
  static class Config {
    @Bean InvoiceRepository invoices() { return mock(InvoiceRepository.class); }
    @Bean ReceiptAccessRepository receiptRepo() { return mock(ReceiptAccessRepository.class); }
    @Bean ServiceFeedbackRepository feedbacks() { return mock(ServiceFeedbackRepository.class); }
    @Bean ReceiptProperties properties() { return new ReceiptProperties(); }
    @Bean ReceiptTokenCodec tokens(ReceiptProperties properties) { return new ReceiptTokenCodec(properties); }
    @Bean ReceiptService receipts(InvoiceRepository invoices, ReceiptAccessRepository receipts,
        ObjectProvider<SaleOrchestratorConfigProvider> providers, ReceiptTokenCodec codec, ReceiptProperties properties) {
      return new ReceiptService(invoices, receipts, providers, codec, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @Bean(destroyMethod = "close") ValidatorFactory validators() { return Validation.buildDefaultValidatorFactory(); }
    @Bean ServiceFeedbackService feedbackService(ReceiptService receipts, ServiceFeedbackRepository feedbacks, ValidatorFactory validators) {
      return new ServiceFeedbackService(receipts, feedbacks, validators.getValidator());
    }
    @Bean ServiceFeedbackController controller(ServiceFeedbackService service) { return new ServiceFeedbackController(service); }
    @Bean InternalTokenService internalTokens() { return mock(InternalTokenService.class); }
    @Bean InternalJwtRequestFilter filter(InternalTokenService service) { return new InternalJwtRequestFilter(service); }
    @Bean InternalJwtAuthenticationEntryPoint entryPoint() { return new InternalJwtAuthenticationEntryPoint(); }
  }
}
