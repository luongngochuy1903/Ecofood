package com.ecofood.app.receipt;

import com.ecofood.app.auth.security.*;
import com.ecofood.app.auth.service.InternalTokenService;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.config.SecurityConfig;
import com.ecofood.app.receipt.controller.*;
import com.ecofood.app.receipt.dto.*;
import com.ecofood.app.receipt.service.ReceiptService;
import io.jsonwebtoken.Jwts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
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
@ActiveProfiles("receipt-security-test")
@ContextConfiguration(classes = ReceiptSecurityTest.Config.class)
@TestPropertySource(properties = {"jwt.secret=dGVzdC1vbmx5LXJlY2VpcHQtc2VjdXJpdHktdGVzdC1rZXk=", "jwt.issuer=app", "jwt.audience=app-api",
    "jwt.tokenValidityInSeconds=1800", "jwt.refreshTokenValidityInSeconds=86400"})
class ReceiptSecurityTest {
  @Autowired WebApplicationContext context;
  @Autowired ReceiptService service;
  @Autowired InternalTokenService internalTokens;
  private MockMvc mvc;

  @BeforeEach void setup() {
    reset(service, internalTokens);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(service.read(anyString())).thenReturn(new PublicReceiptResponse("INV-1", Instant.EPOCH,
        "DP-1", "VND", BigDecimal.TEN, List.of()));
  }

  @Test void guestCanReadWithoutJwtOrCustomerLogin() throws Exception {
    mvc.perform(get("/api/v1/public/receipts").param("token", "opaque"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.invoiceNumber").value("INV-1"));
    verifyNoInteractions(internalTokens);
  }

  @Test void guestReadIgnoresUnrelatedExpiredInternalAuthorization() throws Exception {
    mvc.perform(get("/api/v1/public/receipts").header("Authorization", "Bearer expired-jwt").param("token", "opaque"))
        .andExpect(status().isOk());
    verifyNoInteractions(internalTokens);
  }

  @Test void issueRevokeAndTransactionManagementRemainProtected() throws Exception {
    mvc.perform(post("/api/v1/transactions/abc/receipts").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/v1/transactions/abc/receipts/def")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/transactions")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test void publicExceptionIsOnlyForExactGetEndpoint() throws Exception {
    mvc.perform(post("/api/v1/public/receipts")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/public/receipts/other")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/public/receipts/")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test void invalidReceiptReturns404WithNoStoreThroughRealSecurityChain() throws Exception {
    when(service.read("invalid")).thenThrow(new IllegalArgumentException("Receipt unavailable"));
    mvc.perform(get("/api/v1/public/receipts").param("token", "invalid"))
        .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.message").value("Receipt unavailable"));
  }

  @Test void authenticatedPosCanReachIssueEndpointWithoutMakingItPublic() throws Exception {
    when(internalTokens.verifyToken("pos-token")).thenReturn(Jwts.claims().id("jti")
        .subject("pos-user").add("iss", "app").add("aud", "app-api").build());
    when(service.issue(eq("abc"), any())).thenReturn(new IssueReceiptResponse("receipt", "abc", "https://example/receipt", Instant.EPOCH));
    mvc.perform(post("/api/v1/transactions/abc/receipts").header("Authorization", "Bearer pos-token")
            .contentType("application/json").content("{\"requestId\":\"00000000-0000-4000-8000-000000000001\"}"))
        .andExpect(status().isOk());
    verify(service).issue(eq("abc"), any());
  }

  @Configuration
  @Profile("receipt-security-test")
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfig.class, GlobalExceptionHandler.class})
  static class Config {
    @Bean ReceiptService receiptService() { return mock(ReceiptService.class); }
    @Bean InternalTokenService internalTokenService() { return mock(InternalTokenService.class); }
    @Bean InternalJwtRequestFilter internalJwtRequestFilter(InternalTokenService service) { return new InternalJwtRequestFilter(service); }
    @Bean InternalJwtAuthenticationEntryPoint entryPoint() { return new InternalJwtAuthenticationEntryPoint(); }
    @Bean PublicReceiptController publicController(ReceiptService service) { return new PublicReceiptController(service); }
    @Bean ReceiptController controller(ReceiptService service) { return new ReceiptController(service); }
  }
}
