package com.ecofood.app.tenant.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.TenantLoginInfoResponse;
import com.ecofood.core.dto.TenantOnboardResponse;
import com.ecofood.core.dto.TenantResponse;
import com.ecofood.core.dto.UserResponse;
import com.ecofood.core.service.TenantService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc (no Spring context) — verifies request mapping,
 * @Valid enforcement, and response serialization without needing to wire
 * SecurityConfig/Keycloak beans, which a full @WebMvcTest/@SpringBootTest
 * slice would otherwise require.
 */
@ExtendWith(MockitoExtension.class)
class TenantControllerTest {

  @Mock private TenantService tenantService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new TenantController(tenantService)).build();
  }

  @Test
  void onboardTenantReturns201WithTheServiceResponse() throws Exception {
    when(tenantService.onboardTenant(any()))
        .thenReturn(
            TenantOnboardResponse.builder()
                .tenantId("671f1a2b3c4d5e6f7a8b9c0d")
                .tenantName("Acme Foods")
                .realm("acme-foods-671f1a2b")
                .clientId("671f1a2b3c4d5e6f7a8b9c0d-public")
                .user(UserResponse.builder().userId("u1").build())
                .build());

    String body =
        """
        {
          "tenantName": "Acme Foods",
          "username": "acme.admin",
          "email": "admin@acmefoods.com",
          "partnerTypeCode": "ECOSYSTEM_PARTNER",
          "contact": {
            "contactPartyName": "Jane",
            "contactPartyLastName": "Doe"
          }
        }
        """;

    mockMvc
        .perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.tenantName", is("Acme Foods")))
        .andExpect(jsonPath("$.realm", is("acme-foods-671f1a2b")));
  }

  @Test
  void onboardTenantReturns400WhenRequiredFieldsAreMissing() throws Exception {
    String body = "{}";

    mockMvc
        .perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void onboardTenantReturns400WhenEmailIsInvalid() throws Exception {
    String body =
        """
        {
          "tenantName": "Acme Foods",
          "username": "acme.admin",
          "email": "not-an-email",
          "partnerTypeCode": "ECOSYSTEM_PARTNER"
        }
        """;

    mockMvc
        .perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getLoginInfoReturns200WithTheServiceResponse() throws Exception {
    when(tenantService.getLoginInfoByDomain("acme.ecofood.local"))
        .thenReturn(
            TenantLoginInfoResponse.builder()
                .domain("acme.ecofood.local")
                .realm("acme-foods-671f1a2b")
                .clientId("671f1a2b3c4d5e6f7a8b9c0d-public")
                .tenantId("671f1a2b3c4d5e6f7a8b9c0d")
                .build());

    mockMvc
        .perform(get("/api/v1/tenants/login-info").param("domain", "acme.ecofood.local"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.realm", is("acme-foods-671f1a2b")))
        .andExpect(jsonPath("$.clientId", is("671f1a2b3c4d5e6f7a8b9c0d-public")));
  }

  @Test
  void getLoginInfoReturns400WhenDomainParamIsMissing() throws Exception {
    mockMvc.perform(get("/api/v1/tenants/login-info")).andExpect(status().isBadRequest());
  }

  @Test
  void getTenantDirectoryUsesDefaultsAndReturnsTheServiceResponse() throws Exception {
    when(tenantService.listTenantDirectory(0, 20, "updatedAt,desc", null))
        .thenReturn(
            PageResponse.<TenantResponse>builder()
                .content(List.of(TenantResponse.builder().tenantName("Acme Foods").build()))
                .page(0)
                .size(20)
                .totalElements(1)
                .totalPages(1)
                .build());

    mockMvc
        .perform(get("/api/v1/tenants/directory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].tenantName", is("Acme Foods")))
        .andExpect(jsonPath("$.totalElements", is(1)));
  }

  @Test
  void getTenantDirectoryForwardsPageSizeSortAndSearchParams() throws Exception {
    when(tenantService.listTenantDirectory(2, 10, "tenantName,asc", "acme"))
        .thenReturn(PageResponse.<TenantResponse>builder().content(List.of()).page(2).size(10)
            .totalElements(0).totalPages(0).build());

    mockMvc
        .perform(
            get("/api/v1/tenants/directory")
                .param("page", "2")
                .param("size", "10")
                .param("sort", "tenantName,asc")
                .param("search", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page", is(2)));
  }

  @Test
  void getByIdReturnsTheServiceResponse() throws Exception {
    when(tenantService.getById("671f1a2b3c4d5e6f7a8b9c0d"))
        .thenReturn(TenantResponse.builder().id("671f1a2b3c4d5e6f7a8b9c0d").tenantName("Acme Foods").build());

    mockMvc
        .perform(get("/api/v1/tenants/671f1a2b3c4d5e6f7a8b9c0d"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantName", is("Acme Foods")));
  }

  @Test
  void updateTenantReturns200WithTheServiceResponse() throws Exception {
    when(tenantService.updateTenant(eq("671f1a2b3c4d5e6f7a8b9c0d"), any()))
        .thenReturn(
            TenantResponse.builder().id("671f1a2b3c4d5e6f7a8b9c0d").tenantName("Acme Foods Updated").build());

    String body =
        """
        {
          "tenantName": "Acme Foods Updated"
        }
        """;

    mockMvc
        .perform(put("/api/v1/tenants/671f1a2b3c4d5e6f7a8b9c0d").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantName", is("Acme Foods Updated")));
  }

  @Test
  void updateTenantReturns400WhenTenantNameIsBlank() throws Exception {
    String body = "{}";

    mockMvc
        .perform(put("/api/v1/tenants/671f1a2b3c4d5e6f7a8b9c0d").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }
}
