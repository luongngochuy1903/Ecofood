package com.ecofood.app.userACL.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecofood.app.userACL.dto.RoleResponse;
import com.ecofood.app.userACL.entity.RoleType;
import com.ecofood.app.userACL.service.RoleService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RoleControllerTest {

  @Mock private RoleService roleService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new RoleController(roleService)).build();
  }

  @Test
  void listByActorReturns200WithTheServiceResponse() throws Exception {
    when(roleService.listByActor("ENTERPRISE"))
        .thenReturn(
            List.of(
                RoleResponse.builder().id("r1").code("ENTERPRISE_ADMIN").name("Enterprise Admin")
                    .roleType(RoleType.Administrative).build()));

    mockMvc
        .perform(get("/api/v1/roles").param("actor", "ENTERPRISE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code", is("ENTERPRISE_ADMIN")));
  }
}
