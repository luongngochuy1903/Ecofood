package com.ecofood.app.orchestrator;

import static com.ecofood.app.orchestrator.SaleFixtures.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.config.*;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.orchestrator.controller.PlanSaleOrchestratorController;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.orchestrator.exception.PlanSaleErrorHandler;
import com.ecofood.app.orchestrator.service.PlanSaleOrchestratorServiceImpl;
import com.ecofood.core.dto.PlanSaleOrchestratorDtos;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlanSaleOrchestratorControllerTest {
  private final String base =
      "/api/v1/propositions/" + PROP + "/plans/" + PLAN + "/sale-orchestrator";
  private PlanSaleOrchestratorServiceImpl service;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    service = mock(PlanSaleOrchestratorServiceImpl.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new PlanSaleOrchestratorController(service))
            .setControllerAdvice(new PlanSaleErrorHandler(), new GlobalExceptionHandler())
            .build();
  }

  @Test
  void returns204OnlyForAnAuthorizedPlanWithNoConfiguration() throws Exception {
    when(service.get(PROP, PLAN)).thenReturn(Optional.empty());
    mvc.perform(get(base)).andExpect(status().isNoContent());
    verify(service).get(PROP, PLAN);
  }

  @Test
  void existingAuthorizationAndConflictErrorsKeepTheirStatus() throws Exception {
    when(service.get(PROP, PLAN)).thenThrow(new ForbiddenException("Tenant role required"));
    mvc.perform(get(base)).andExpect(status().isForbidden());
    when(service.activate(eq(PROP), eq(PLAN), any()))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                HttpStatus.CONFLICT, "Stale revision"));
    mvc.perform(
            post(base + "/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectsScriptRulesAndUnsupportedTypesBeforeReachingService() throws Exception {
    for (String field :
        List.of(
            "{\"name\":\"bad\",\"type\":\"STRING\",\"script\":\"execute()\"}",
            "{\"name\":\"bad\",\"type\":\"SCRIPT\"}")) {
      mvc.perform(
              post(base)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"configuration\":{\"name\":\"Sale\",\"maxItems\":100,\"postWorkflows\":[],\"payloadSchema\":{\"fields\":["
                          + field
                          + "]}}}"))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }

  @Test
  void acceptsNestedSchemaAndDoesNotAcceptPlanIdentityFromRequestBody() throws Exception {
    when(service.create(eq(PROP), eq(PLAN), any())).thenReturn(new PlanSaleOrchestrator());
    mvc.perform(
            post(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
{"tenantId":"attacker","planId":"other","configuration":{"name":"Sale","maxItems":100,"postWorkflows":[],
  "payloadSchema":{"fields":[{"name":"items","type":"ARRAY","required":true,"items":{"type":"OBJECT","fields":[]}}]}}}
"""))
        .andExpect(status().isCreated());
    verify(service)
        .create(
            eq(PROP),
            eq(PLAN),
            argThat(r -> !r.configuration().payloadSchema().fields().get(0).items().required()));
  }

  @Test
  void validatesDraftWithoutActivatingIt() throws Exception {
    when(service.validate(eq(PROP), eq(PLAN), any()))
        .thenReturn(new PlanSaleOrchestratorDtos.Validation(false, List.of("Duplicate field")));
    mvc.perform(
            post(base + "/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.errors[0]").value("Duplicate field"));
    verify(service, never()).activate(any(), any(), any());
  }

  @Test
  void acceptsConfigurationWithoutPosAndIgnoresLegacyPosFields() throws Exception {
    when(service.create(eq(PROP), eq(PLAN), any())).thenReturn(new PlanSaleOrchestrator());
    mvc.perform(
            post(base)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
{"configuration":{"name":"Sale","maxItems":100,"postWorkflows":[],"posBindings":[{"posId":"legacy","authenticatedSubject":"legacy"}],"payloadSchema":{"fields":[]}}}
"""))
        .andExpect(status().isCreated());
    verify(service)
        .create(
            eq(PROP),
            eq(PLAN),
            argThat(
                r ->
                    r.configuration().maxItems() == 100
                        && r.configuration().name().equals("Sale")));
  }
}
