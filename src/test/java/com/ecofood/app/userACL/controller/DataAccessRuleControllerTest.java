package com.ecofood.app.userACL.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecofood.core.dto.DataAccessRuleDetailResponse;
import com.ecofood.core.dto.DataAccessRuleDirectoryResponse;
import com.ecofood.core.dto.DataAccessRuleResponse;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.service.DataAccessRuleService;
import com.ecofood.core.enums.AccessLevelEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DataAccessRuleControllerTest {

  @Mock private DataAccessRuleService dataAccessRuleService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new DataAccessRuleController(dataAccessRuleService)).build();
  }

  @Test
  void createReturns201WithTheServiceResponse() throws Exception {
    when(dataAccessRuleService.create(any()))
        .thenReturn(
            DataAccessRuleResponse.builder()
                .id("g1")
                .code("DAR0001")
                .ruleName("Billing Viewer")
                .accessLevel(AccessLevelEnum.FULL_ACCESS)
                .build());

    String body =
        """
        {
          "businessEntityId": "671f1a2b3c4d5e6f7a8b9c0d",
          "ruleName": "Billing Viewer",
          "description": "Lets partners view billing dashboards.",
          "accessLevel": "FULL_ACCESS"
        }
        """;

    mockMvc
        .perform(post("/api/v1/data-access-rules").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code", is("DAR0001")));
  }

  @Test
  void createReturns400WhenRuleNameIsBlank() throws Exception {
    String body =
        """
        {
          "businessEntityId": "671f1a2b3c4d5e6f7a8b9c0d",
          "ruleName": "",
          "description": "Lets partners view billing dashboards.",
          "accessLevel": "FULL_ACCESS"
        }
        """;

    mockMvc
        .perform(post("/api/v1/data-access-rules").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createReturns400WhenAccessLevelIsMissing() throws Exception {
    String body =
        """
        {
          "businessEntityId": "671f1a2b3c4d5e6f7a8b9c0d",
          "ruleName": "Billing Viewer",
          "description": "Lets partners view billing dashboards."
        }
        """;

    mockMvc
        .perform(post("/api/v1/data-access-rules").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getDirectoryUsesDefaultsAndReturnsTheServiceResponse() throws Exception {
    when(dataAccessRuleService.listDirectory(0, 20, "updatedAt,desc", null))
        .thenReturn(
            PageResponse.<DataAccessRuleDirectoryResponse>builder()
                .content(java.util.List.of(DataAccessRuleDirectoryResponse.builder().code("DAR0001").build()))
                .page(0)
                .size(20)
                .totalElements(1)
                .totalPages(1)
                .build());

    mockMvc
        .perform(get("/api/v1/data-access-rules/directory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].code", is("DAR0001")));
  }

  @Test
  void getDirectoryForwardsPageSizeSortAndSearchParams() throws Exception {
    when(dataAccessRuleService.listDirectory(2, 10, "name,asc", "billing"))
        .thenReturn(
            PageResponse.<DataAccessRuleDirectoryResponse>builder()
                .content(java.util.List.of())
                .page(2)
                .size(10)
                .totalElements(0)
                .totalPages(0)
                .build());

    mockMvc
        .perform(
            get("/api/v1/data-access-rules/directory")
                .param("page", "2")
                .param("size", "10")
                .param("sort", "name,asc")
                .param("search", "billing"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page", is(2)));
  }

  @Test
  void getByIdReturns200WithTheServiceResponse() throws Exception {
    when(dataAccessRuleService.getById("g1"))
        .thenReturn(DataAccessRuleDetailResponse.builder().id("g1").ruleName("Billing Viewer").editable(true).build());

    mockMvc
        .perform(get("/api/v1/data-access-rules/g1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleName", is("Billing Viewer")))
        .andExpect(jsonPath("$.editable", is(true)));
  }

  @Test
  void updateReturns200WithTheServiceResponse() throws Exception {
    when(dataAccessRuleService.update(eq("g1"), any()))
        .thenReturn(DataAccessRuleDetailResponse.builder().id("g1").ruleName("Renamed Rule").build());

    String body =
        """
        {
          "ruleName": "Renamed Rule",
          "description": "Updated description.",
          "accessLevel": "FULL_ACCESS"
        }
        """;

    mockMvc
        .perform(put("/api/v1/data-access-rules/g1").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleName", is("Renamed Rule")));
  }

  @Test
  void updateReturns400WhenRuleNameIsBlank() throws Exception {
    String body =
        """
        {
          "ruleName": "",
          "description": "Updated description.",
          "accessLevel": "FULL_ACCESS"
        }
        """;

    mockMvc
        .perform(put("/api/v1/data-access-rules/g1").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }
}
