package com.ecofood.app.customer.auth;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.complaint.*;
import com.ecofood.app.complaint.ComplaintContracts.Permission;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Arrays;
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
@ActiveProfiles({"customer-auth-security-test", "complaint-processing-security-test"})
@ContextConfiguration(classes = ComplaintProcessingSecurityTest.Config.class)
@TestPropertySource(properties = {"jwt.secret=" + CustomerAuthSecurityTest.SECRET, "jwt.issuer=app", "jwt.audience=app-api",
    "jwt.tokenValidityInSeconds=1800", "jwt.refreshTokenValidityInSeconds=86400"})
class ComplaintProcessingSecurityTest {
  @Autowired WebApplicationContext context; @Autowired ComplaintProcessingService service; MockMvc mvc;
  final String base = "/api/v1/complaints/000000000000000000000031";
  @BeforeEach void setup() { reset(service); mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
  String token(boolean customer) {
    return Jwts.builder().subject("000000000000000000000014").id("processing-jti").claim("identityType", customer ? "CUSTOMER" : "EMPLOYEE")
        .claim("tokenType", "access").claim("tenantId", CustomerIdentityServiceTest.TENANT)
        .claim("permissions", Arrays.stream(Permission.values()).map(Enum::name).toList())
        .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(CustomerAuthSecurityTest.SECRET))).compact();
  }
  @Test void allManagementReadsAndActionsRequireAuthenticationAndRejectCustomerEvenWithSpoofedPermissions() throws Exception {
    for (String path : new String[]{"/api/v1/complaints", "/api/v1/complaints/options", base, base + "/history"}) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).header("Authorization", "Bearer " + token(true))).andExpect(status().isForbidden());
    }
    for (String action : new String[]{"assign", "start", "request-information", "resume", "resolve", "escalate"}) {
      mvc.perform(post(base + "/" + action).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
      mvc.perform(post(base + "/" + action).header("Authorization", "Bearer " + token(true)).contentType("application/json").content("{}"))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(service);
  }
  @Test void employeeJwtPassesBoundaryAndCannotUseForgedTenantBody() throws Exception {
    mvc.perform(get(base).header("Authorization", "Bearer " + token(false))).andExpect(status().isOk()); verify(service).detail(anyString());
    mvc.perform(post(base + "/escalate").header("Authorization", "Bearer " + token(false)).contentType("application/json")
        .content("{\"requestId\":\"x\",\"expectedVersion\":0,\"reason\":\"Escalate\",\"tenantId\":\"forged\"}"))
        .andExpect(status().isBadRequest()); verifyNoMoreInteractions(service);
  }
  @Configuration @Profile("complaint-processing-security-test") @Import({CustomerAuthSecurityTest.Config.class, ComplaintProcessingController.class, ComplaintIntakeErrors.class})
  static class Config { @Bean ComplaintProcessingService service() { return mock(ComplaintProcessingService.class); } }
}
