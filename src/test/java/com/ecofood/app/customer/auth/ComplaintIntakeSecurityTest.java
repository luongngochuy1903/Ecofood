package com.ecofood.app.customer.auth;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.complaint.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ActiveProfiles({"customer-auth-security-test", "complaint-security-test"})
@ContextConfiguration(classes = ComplaintIntakeSecurityTest.Config.class)
@TestPropertySource(properties = {"jwt.secret=" + CustomerAuthSecurityTest.SECRET, "jwt.issuer=app", "jwt.audience=app-api",
    "jwt.tokenValidityInSeconds=1800", "jwt.refreshTokenValidityInSeconds=86400"})
class ComplaintIntakeSecurityTest {
  @Autowired WebApplicationContext context;
  @Autowired ComplaintIntakeService service;
  MockMvc mvc;
  @BeforeEach void setup() { reset(service); mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
  String customer() {
    return Jwts.builder().subject("000000000000000000000011").id("complaint-jti").claim("identityType", "CUSTOMER").claim("tokenType", "access")
        .claim("tenantId", CustomerIdentityServiceTest.TENANT).claim("permissions", List.of("COMPLAINT_CREATE", "COMPLAINT_READ"))
        .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(CustomerAuthSecurityTest.SECRET))).compact();
  }
  @Test void publicIntakeAndProofTrackingNeedNeitherLoginNorValidEmployeeJwt() throws Exception {
    String body = "{\"receiptToken\":\"" + "a".repeat(43) + "\",\"requestId\":\"one\",\"categoryCode\":\"SERVICE_ISSUE\",\"subject\":\"Issue\",\"description\":\"Description\"}";
    mvc.perform(post("/api/v1/public/invoice-complaints").header("Authorization", "Bearer intentionally-invalid").contentType("application/json").content(body)).andExpect(status().isCreated());
    mvc.perform(post("/api/v1/public/complaints/track").contentType("application/json").content("{\"complaintId\":\"000000000000000000000031\",\"accessSecret\":\"" + "a".repeat(43) + "\"}"))
        .andExpect(status().isOk()); verify(service).guest(anyString(), any()); verify(service).track(any());
  }
  @Test void privateIntakeRequiresLoginAndCustomerCannotEnterTenantOrWorkflowRoutes() throws Exception {
    mvc.perform(get("/api/v1/customers/me/complaints")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/complaints/internal").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/complaints/internal/mine").header("Authorization", "Bearer " + customer())).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/customers/me/complaints/000000000000000000000031/resolve").header("Authorization", "Bearer " + customer()).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden()); verifyNoInteractions(service);
  }
  @Test void exactCustomerReadAndCreateRoutesPassJwtBoundaryButOwnershipStaysInService() throws Exception {
    String header = "Bearer " + customer();
    mvc.perform(get("/api/v1/customers/me/complaints").header("Authorization", header)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/customers/me/complaints/000000000000000000000031").header("Authorization", header)).andExpect(status().isOk());
    mvc.perform(post("/api/v1/customers/me/complaints/platform").header("Authorization", header).contentType("application/json")
        .content("{\"requestId\":\"one\",\"categoryCode\":\"SYSTEM_ERROR\",\"subject\":\"Issue\",\"description\":\"Description\"}"))
        .andExpect(status().isCreated()); verify(service).customerMine(0, 10, null, null, null, null, null); verify(service).customerDetail("000000000000000000000031"); verify(service).platform(any());
  }
  @Configuration @Profile("complaint-security-test") @Import({CustomerAuthSecurityTest.Config.class, ComplaintIntakeController.class, ComplaintIntakeErrors.class})
  static class Config { @Bean ComplaintIntakeService intake() { return mock(ComplaintIntakeService.class); } }
}
