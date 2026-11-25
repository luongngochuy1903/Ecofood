package com.ecofood.app.orchestrator;

import static com.ecofood.app.orchestrator.SaleFixtures.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.orchestrator.controller.PlanSaleOrchestratorController;
import com.ecofood.app.orchestrator.exception.PlanSaleErrorHandler;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.dto.PlanSaleOrchestratorDirectoryResponse;
import com.ecofood.core.dto.PlanSaleOrchestratorResponse;
import com.ecofood.core.service.PlanSaleOrchestratorService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class PlanSaleOrchestratorControllerTest {
  private final String base = "/api/v1/propositions/" + PROP + "/sale-orchestrators";
  private PlanSaleOrchestratorService service;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    service = mock(PlanSaleOrchestratorService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new PlanSaleOrchestratorController(service))
            .setControllerAdvice(new PlanSaleErrorHandler(), new GlobalExceptionHandler())
            .build();
  }

  private PlanSaleOrchestratorResponse response() {
    return new PlanSaleOrchestratorResponse(
        "orch", 0L, PROP, "Sale A", "desc",
        List.of(new PlanSaleOrchestratorResponse.PlanRef(PLAN, "PL00001", "Chicken Plan", null)),
        List.of(), List.of(), List.of(),
        Instant.now(), Instant.now(), "employee", "employee");
  }

  @Test
  void returnsOrchestratorDetail() throws Exception {
    when(service.get(PROP, "orch")).thenReturn(response());
    mvc.perform(get(base + "/orch"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("orch"))
        .andExpect(jsonPath("$.plans[0].planId").value(PLAN));
  }

  @Test
  void propagatesForbiddenAndConflictStatusFromService() throws Exception {
    when(service.get(PROP, "orch")).thenThrow(new ForbiddenException("Tenant role required"));
    mvc.perform(get(base + "/orch")).andExpect(status().isForbidden());
    when(service.update(eq(PROP), eq("orch"), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Sale Orchestrator changed"));
    mvc.perform(
            put(base + "/orch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedRevision\":0,\"name\":\"Sale A\",\"planIds\":[\"" + PLAN + "\"]}"))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectsRequestWithNoPlansOrBlankName() throws Exception {
    mvc.perform(
            post(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sale A\",\"planIds\":[]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"planIds\":[\"" + PLAN + "\"]}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void createsOrchestratorAndReturns201() throws Exception {
    when(service.create(eq(PROP), any())).thenReturn(response());
    mvc.perform(
            post(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Sale A\",\"description\":\"desc\",\"planIds\":[\""
                        + PLAN
                        + "\"]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Sale A"));
    verify(service)
        .create(eq(PROP), argThat(r -> r.name().equals("Sale A") && r.planIds().equals(List.of(PLAN))));
  }

  @Test
  void returnsDirectoryPage() throws Exception {
    var row =
        new PlanSaleOrchestratorDirectoryResponse(
            "orch", "Sale A", "desc", List.of(PLAN), List.of("Chicken Plan"), Instant.now());
    when(service.directory(PROP, 0, 20, "name,asc", null))
        .thenReturn(
            PageResponse.<PlanSaleOrchestratorDirectoryResponse>builder()
                .content(List.of(row))
                .page(0)
                .size(20)
                .totalElements(1)
                .totalPages(1)
                .build());
    mvc.perform(get(base + "/directory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value("orch"));
  }
}
