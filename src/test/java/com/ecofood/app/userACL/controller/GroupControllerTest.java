package com.ecofood.app.userACL.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecofood.app.userACL.dto.GroupResponse;
import com.ecofood.app.userACL.dto.GroupRoleResponse;
import com.ecofood.app.userACL.dto.GroupWithRolesResponse;
import com.ecofood.app.userACL.service.GroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

  @Mock private GroupService groupService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new GroupController(groupService)).build();
  }

  @Test
  void createGroupReturns201WithTheServiceResponse() throws Exception {
    when(groupService.createGroupWithRoles(any()))
        .thenReturn(
            GroupWithRolesResponse.builder()
                .group(GroupResponse.builder().id("g1").name("Tenant Admin Roles").code("TENANT").build())
                .groupRole(GroupRoleResponse.builder().groupId("g1").roles(java.util.List.of()).build())
                .build());

    String body =
        """
        {
          "group": {"name": "Tenant Admin Roles", "code": "TENANT"},
          "roles": [{"name": "Tenant Admin", "code": "TENANT_ADMIN", "roleType": "Administrative"}]
        }
        """;

    mockMvc
        .perform(post("/api/v1/groups").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.group.code", is("TENANT")));
  }

  @Test
  void createGroupReturns400WhenRolesIsEmpty() throws Exception {
    String body =
        """
        {
          "group": {"name": "Tenant Admin Roles", "code": "TENANT"},
          "roles": []
        }
        """;

    mockMvc
        .perform(post("/api/v1/groups").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createGroupReturns400WhenGroupFieldIsMissing() throws Exception {
    String body =
        """
        {
          "roles": [{"name": "Tenant Admin", "code": "TENANT_ADMIN", "roleType": "Administrative"}]
        }
        """;

    mockMvc
        .perform(post("/api/v1/groups").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }
}
