package com.ecofood.app.complaint;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.Operation;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ComplaintProcessingHttpTest {
  ComplaintProcessingService service; MockMvc mvc;
  final String id = "000000000000000000000031", base = "/api/v1/complaints/" + id;
  final String action = "\"requestId\":\"process-1\",\"expectedVersion\":0,\"reason\":\"Please investigate\"";
  @BeforeEach void setup() {
    service = mock(ComplaintProcessingService.class);
    mvc = MockMvcBuilders.standaloneSetup(new ComplaintProcessingController(service)).setControllerAdvice(new ComplaintIntakeErrors()).build();
  }
  @Test void actionRoutesSelectServerOperationAndResponsesAreNotCached() throws Exception {
    String[] paths = {"start", "request-information", "resume", "resolve", "escalate"};
    Operation[] operations = {Operation.START_PROCESSING, Operation.REQUEST_INFORMATION, Operation.RESUME_PROCESSING, Operation.RESOLVE, Operation.ESCALATE};
    for (int i = 0; i < paths.length; i++) {
      mvc.perform(post(base + "/" + paths[i]).contentType("application/json").content("{" + action + "}"))
          .andExpect(status().isOk()).andExpect(header().string("Referrer-Policy", "no-referrer"));
      verify(service).action(eq(id), any(), eq(operations[i]));
    }
    mvc.perform(post(base + "/assign").contentType("application/json").content("{" + action + ",\"assigneeUserId\":\"000000000000000000000014\"}"))
        .andExpect(status().isOk()); verify(service).assign(eq(id), any());
  }
  @Test void spoofedQueueTenantLevelStatusPolicyAndVisibilityAreRejected() throws Exception {
    for (String field : new String[]{"tenantId", "processingTenantId", "currentQueueId", "queueId", "level", "escalationLevel", "status", "routingPolicyId", "visibility", "actorId"}) {
      mvc.perform(post(base + "/escalate").contentType("application/json").content("{" + action + ",\"" + field + "\":null}"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(post(base + "/start").contentType("application/json").content("{" + action + ",\"assigneeUserId\":\"000000000000000000000014\"}"))
        .andExpect(status().isBadRequest()); verifyNoInteractions(service);
  }
  @Test void missingVersionInvalidVersionOversizedReasonAndInvalidAssigneeNeverReachWorkflow() throws Exception {
    for (String body : new String[]{"{\"requestId\":\"x\",\"reason\":\"Reason\"}", "{" + action.replace("Version\":0", "Version\":-1") + "}",
        "{" + action.replace("Please investigate", "x".repeat(1001)) + "}"}) {
      mvc.perform(post(base + "/resolve").contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }
    mvc.perform(post(base + "/assign").contentType("application/json").content("{" + action + ",\"assigneeUserId\":\"other-tenant\"}"))
        .andExpect(status().isBadRequest()); verifyNoInteractions(service);
  }
  @Test void listCannotChooseTenantQueueOrOverrideScopeAndPaginationIsBounded() throws Exception {
    for (String name : new String[]{"tenantId", "level", "customerId"}) mvc.perform(get("/api/v1/complaints").param(name, "forged")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/complaints").param("page", "0", "1")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/complaints").param("size", "101")).andExpect(status().isBadRequest());
    mvc.perform(get(base + "/history").param("tenantId", "forged")).andExpect(status().isBadRequest()); verifyNoInteractions(service);
  }
  @Test void providerErrorsAreSanitized() throws Exception {
    when(service.detail(id)).thenThrow(new IllegalStateException("private credential"));
    mvc.perform(get(base)).andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private credential"))));
  }
}
