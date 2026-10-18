package com.ecofood.app.userACL.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecofood.app.userACL.dto.PermissionWithRoleResponse;
import com.ecofood.app.userACL.dto.RolePermissionResponse;
import com.ecofood.app.userACL.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PermissionControllerTest {

  @Mock private PermissionService permissionService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new PermissionController(permissionService)).build();
  }

  @Test
  void createPermissionReturns201WithTheServiceResponse() throws Exception {
    when(permissionService.createPermissionsWithRole(any()))
        .thenReturn(
            PermissionWithRoleResponse.builder()
                .permissions(java.util.List.of())
                .rolePermission(RolePermissionResponse.builder().roleId("r1").permissions(java.util.List.of()).build())
                .build());

    String body =
        """
        {
          "roleId": "671f0a1a2b3c4d5e6f7a8b05",
          "permissions": [{"name": "View Products", "code": "products.read.any"}]
        }
        """;

    mockMvc
        .perform(post("/api/v1/permissions").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.rolePermission.roleId", is("r1")));
  }

  @Test
  void createPermissionReturns400WhenRoleIdIsMissing() throws Exception {
    String body =
        """
        {
          "permissions": [{"name": "View Products", "code": "products.read.any"}]
        }
        """;

    mockMvc
        .perform(post("/api/v1/permissions").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createPermissionReturns400WhenPermissionsIsEmpty() throws Exception {
    String body =
        """
        {
          "roleId": "671f0a1a2b3c4d5e6f7a8b05",
          "permissions": []
        }
        """;

    mockMvc
        .perform(post("/api/v1/permissions").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }
}
