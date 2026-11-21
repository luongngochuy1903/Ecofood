package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.config.GlobalExceptionHandler;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ComplaintApiTest {
  @Test void customerCannotSupplyTypeInternalMissingInvoiceOrUnknownState() throws Exception {
    var service=mock(ComplaintService.class); var mvc=MockMvcBuilders.standaloneSetup(new ComplaintController(service)).setControllerAdvice(new ComplaintApiErrors(),new GlobalExceptionHandler()).build();
    mvc.perform(post("/api/v1/customers/me/complaints").contentType(MediaType.APPLICATION_JSON).content("{\"complaintType\":\"INTERNAL\",\"subject\":\"x\",\"description\":\"x\",\"requestId\":\"x\"}")).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/customers/me/complaints").contentType(MediaType.APPLICATION_JSON).content("{\"complaintType\":\"TENANT\",\"subject\":\"\",\"description\":\"x\",\"requestId\":\"x\"}")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/complaints?status=ESCALATED")).andExpect(status().isBadRequest()); verifyNoInteractions(service);
  }
  @Test void customerCannotSetStatusOwnerOrRouteAndOutboxIsNotExposed() throws Exception {
    var service=mock(ComplaintService.class); var c=new Complaint(); c.setId("c"); c.setHandoffs(List.of(new Complaint.Handoff(null,true)));
    when(service.create(any())).thenReturn(c);
    var mvc=MockMvcBuilders.standaloneSetup(new ComplaintController(service)).setControllerAdvice(new ComplaintApiErrors(),new GlobalExceptionHandler()).build();
    mvc.perform(post("/api/v1/customers/me/complaints").contentType(MediaType.APPLICATION_JSON).content("{\"complaintType\":\"ECOSYSTEM\",\"subject\":\"x\",\"description\":\"y\",\"requestId\":\"123\",\"tenantId\":\"spoof\",\"customerId\":\"other\",\"status\":\"CLOSED\",\"assignedTo\":\"spoof\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.handoffs").doesNotExist());
    verify(service).create(argThat(r->r.complaintType()==Complaint.Type.ECOSYSTEM && r.invoiceId()==null));
  }
  @Test void customerCannotUseAdminActionsAndLegacyRoutingPathsHaveNoWriter() throws Exception {
    var service=mock(ComplaintService.class); var mvc=MockMvcBuilders.standaloneSetup(new ComplaintController(service)).build();
    mvc.perform(post("/api/v1/customers/me/complaints/c/transition").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/complaint/escalate").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound()); verifyNoInteractions(service);
  }
}
