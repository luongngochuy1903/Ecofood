package com.ecofood.app.userACL.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecofood.core.dto.AssignTenantRolesResponse;
import com.ecofood.core.dto.UserResponse;
import com.ecofood.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

  @Mock private UserService userService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();
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
}
