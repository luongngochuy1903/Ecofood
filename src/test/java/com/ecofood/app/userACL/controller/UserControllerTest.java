package com.ecofood.app.userACL.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecofood.core.dto.AssignTenantRolesResponse;
import com.ecofood.core.dto.EligibleTenantResponse;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.UserDirectoryResponse;
import com.ecofood.core.dto.UserResponse;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.UserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

  // 32+ raw bytes, base64-encoded — matches AuthenticationContext/jwt.secret's
  // documented >=32-byte requirement for HS256 (see AuthenticationContextTest).
  private static final String SECRET =
      java.util.Base64.getEncoder().encodeToString("unit-test-only-hmac-secret-0123456789".getBytes());

  @Mock private UserService userService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();
  }

  @AfterEach
  void unloadAuthenticationContext() {
    AuthenticationContext.unload();
  }

  /** getEligibleTenants() reads the caller's id from AuthenticationContext, same as a real InternalJwtRequestFilter-processed request would. */
  private void authenticateAs(String userId) {
    SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    String token =
        Jwts.builder()
            .subject(userId)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(key)
            .compact();
    AuthenticationContext.setToken(token);
    AuthenticationContext.setSecret(SECRET);
  }

  @Test
  void createUserReturns201WithTheServiceResponse() throws Exception {
    when(userService.createUser(any()))
        .thenReturn(
            UserResponse.builder()
                .userId("u1")
                .username("jane.doe")
                .email("jane@example.com")
                .userCategoryCode("CUSTOMER")
                .tenantAssignments(java.util.List.of())
                .build());

    String body =
        """
        {
          "username": "jane.doe",
          "email": "jane@example.com",
          "tenantAssignments": [{"tenantId": "671f1a2b3c4d5e6f7a8b9c0d"}]
        }
        """;

    mockMvc
        .perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username", is("jane.doe")));
  }

  @Test
  void createUserReturns400WhenEmailIsInvalid() throws Exception {
    String body =
        """
        {
          "username": "jane.doe",
          "email": "not-an-email",
          "tenantAssignments": [{"tenantId": "671f1a2b3c4d5e6f7a8b9c0d"}]
        }
        """;

    mockMvc
        .perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createUserReturns400WhenTenantAssignmentsIsEmpty() throws Exception {
    String body =
        """
        {
          "username": "jane.doe",
          "email": "jane@example.com",
          "tenantAssignments": []
        }
        """;

    mockMvc
        .perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void assignTenantRolesReturns200WithTheServiceResponse() throws Exception {
    when(userService.assignTenantRoles(anyString(), any()))
        .thenReturn(AssignTenantRolesResponse.builder().userId("u1").assignments(java.util.List.of()).build());

    String body =
        """
        {
          "tenantAssignments": [{"tenantId": "671f1a2b3c4d5e6f7a8b9c0d", "roleIds": ["671f0a1a2b3c4d5e6f7a8b05"]}]
        }
        """;

    mockMvc
        .perform(post("/api/v1/users/u1/tenant-assignments").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId", is("u1")));
  }

  @Test
  void assignTenantRolesReturns400WhenRoleIdsIsEmpty() throws Exception {
    String body =
        """
        {
          "tenantAssignments": [{"tenantId": "671f1a2b3c4d5e6f7a8b9c0d", "roleIds": []}]
        }
        """;

    mockMvc
        .perform(post("/api/v1/users/u1/tenant-assignments").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getEligibleTenantsReturns200WithTheServiceResponseForTheAuthenticatedCaller() throws Exception {
    authenticateAs("671f1a2b3c4d5e6f7a8b9c0d");
    when(userService.getEligibleTenants("671f1a2b3c4d5e6f7a8b9c0d"))
        .thenReturn(
            java.util.List.of(
                EligibleTenantResponse.builder()
                    .tenantId("671f1a2b3c4d5e6f7a8b9c0d")
                    .tenantName("Acme Foods")
                    .tenantCode("acme-foods-671f1a2b")
                    .build()));

    mockMvc
        .perform(get("/api/v1/users/eligible-tenants"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].tenantName", is("Acme Foods")))
        .andExpect(jsonPath("$[0].tenantCode", is("acme-foods-671f1a2b")));
  }

  @Test
  void getUserDirectoryUsesDefaultsAndReturnsTheServiceResponse() throws Exception {
    when(userService.listUserDirectory(0, 20, "updatedAt,desc", null))
        .thenReturn(
            PageResponse.<UserDirectoryResponse>builder()
                .content(
                    java.util.List.of(
                        UserDirectoryResponse.builder().firstName("Jane").lastName("Doe").build()))
                .page(0)
                .size(20)
                .totalElements(1)
                .totalPages(1)
                .build());

    mockMvc
        .perform(get("/api/v1/users/directory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].firstName", is("Jane")))
        .andExpect(jsonPath("$.totalElements", is(1)));
  }

  @Test
  void getUserDirectoryForwardsPageSizeSortAndSearchParams() throws Exception {
    when(userService.listUserDirectory(2, 10, "firstName,asc", "jane"))
        .thenReturn(
            PageResponse.<UserDirectoryResponse>builder()
                .content(java.util.List.of())
                .page(2)
                .size(10)
                .totalElements(0)
                .totalPages(0)
                .build());

    mockMvc
        .perform(
            get("/api/v1/users/directory")
                .param("page", "2")
                .param("size", "10")
                .param("sort", "firstName,asc")
                .param("search", "jane"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page", is(2)));
  }
}
